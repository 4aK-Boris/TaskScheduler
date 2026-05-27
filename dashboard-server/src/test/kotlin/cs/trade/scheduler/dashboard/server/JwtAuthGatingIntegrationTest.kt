@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package cs.trade.scheduler.dashboard.server

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.rabbitmq.client.ConnectionFactory
import cs.trade.scheduler.dashboard.server.api.routes.configureDashboardRouting
import cs.trade.scheduler.engine.infra.infrastructure.leader.LeaderElection
import cs.trade.scheduler.runner.configureHealthRouting
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.auth.Authentication
import io.ktor.server.auth.UserIdPrincipal
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.basic
import io.ktor.server.auth.jwt.JWTPrincipal
import io.ktor.server.auth.jwt.jwt
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.koin.ktor.plugin.Koin
import java.util.Base64
import java.util.Date
import kotlin.time.Duration.Companion.milliseconds

/**
 * End-to-end coverage for the dashboard JWT bearer-auth path (Phase 3). Mirrors
 * [BasicAuthGatingIntegrationTest]'s shape — inline auth install in `testApplication`
 * because the real wiring in `standalone-runner.Application.configureKtor` is private.
 *
 * Asserts:
 *  1. Valid HMAC256 token with correct iss/aud → 200 on /api/jobs.
 *  2. Wrong signature → 401.
 *  3. Wrong issuer → 401.
 *  4. Wrong audience → 401.
 *  5. Expired token → 401.
 *  6. Token with no `sub` claim → 401 (audit attribution would fall back to anonymous,
 *     defeating the purpose of bearer auth).
 *  7. BasicAuth still works when both are configured (multi-method authenticator).
 *  8. NEITHER credential type → 401.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class JwtAuthGatingIntegrationTest {

    // Credentials shared between the verifier (server side) and the JWT builder (test side).
    // Real deploys load these from env / vault.
    private val jwtSecret = "test-jwt-secret-please-rotate"
    private val jwtIssuer = "scheduler-test"
    private val jwtAudience = "scheduler-dashboard"
    private val basicUser = "admin"
    private val basicPass = "basic-pass-test"

    private lateinit var storage: DashboardTestSupport.Storage

    @BeforeAll
    fun setUp() {
        storage = DashboardTestSupport.startStorage()
    }

    @AfterAll
    fun tearDown() {
        DashboardTestSupport.stopKoinSafe()
        storage.shutdown()
    }

    /**
     * Mirrors `Application.configureKtor`'s JWT + Basic install pattern. We declare BOTH
     * authenticators so a single test class can exercise "JWT alongside Basic" scenarios.
     * Real apps may pick one or both based on operator preference.
     */
    private fun authedTestApp(block: suspend (io.ktor.client.HttpClient) -> Unit) = testApplication {
        val leader = LeaderElection(
            jdbcUrl = storage.jdbcUrl,
            jdbcUser = storage.pgUser,
            jdbcPassword = storage.pgPass,
            key = 0x7FFF_FFFF_0004L,
            electionInterval = 5_000.milliseconds,
        )
        val rabbitFactory = ConnectionFactory().apply {
            host = "127.0.0.1"; port = 1; connectionTimeout = 250
            isAutomaticRecoveryEnabled = false
        }
        application {
            install(ContentNegotiation) { json() }
            install(io.ktor.server.websocket.WebSockets)
            install(Authentication) {
                basic("dashboard") {
                    realm = "TaskScheduler dashboard"
                    validate { creds ->
                        if (creds.name == basicUser && creds.password == basicPass) UserIdPrincipal(creds.name) else null
                    }
                }
                jwt("jwt") {
                    realm = "TaskScheduler dashboard"
                    verifier(
                        JWT.require(Algorithm.HMAC256(jwtSecret))
                            .withIssuer(jwtIssuer)
                            .withAudience(jwtAudience)
                            .build(),
                    )
                    validate { creds ->
                        val sub = creds.payload.subject
                        if (sub.isNullOrBlank()) null else JWTPrincipal(creds.payload)
                    }
                }
            }
            install(Koin) {
                modules(*DashboardTestSupport.dashboardModules(storage.dataSource, nodeId = "test-jwt"))
            }
            routing {
                configureHealthRouting(
                    dataSource = storage.dataSource,
                    rabbitFactory = rabbitFactory,
                    leader = leader,
                )
                // Multi-method wrap — either Basic or JWT principals pass.
                authenticate("dashboard", "jwt") {
                    configureDashboardRouting()
                }
            }
        }
        startApplication()
        block(this.client)
    }

    // --- Token builders -----------------------------------------------------

    private fun signed(
        secret: String = jwtSecret,
        issuer: String? = jwtIssuer,
        audience: String? = jwtAudience,
        subject: String? = "service-account",
        expiresAt: Date? = Date(System.currentTimeMillis() + 60_000),
    ): String {
        val builder = JWT.create()
        if (issuer != null) builder.withIssuer(issuer)
        if (audience != null) builder.withAudience(audience)
        if (subject != null) builder.withSubject(subject)
        if (expiresAt != null) builder.withExpiresAt(expiresAt)
        return builder.sign(Algorithm.HMAC256(secret))
    }

    // --- Tests --------------------------------------------------------------

    @Test
    fun `valid JWT bearer token grants access to api jobs`() = runBlocking {
        authedTestApp { client ->
            val token = signed()
            val response = client.get("/api/jobs") {
                header(HttpHeaders.Authorization, "Bearer $token")
            }
            assertEquals(
                HttpStatusCode.OK, response.status,
                "expected 200 with a valid token, got ${response.status}: ${response.bodyAsText()}",
            )
        }
    }

    @Test
    fun `wrong signature is rejected with 401`() = runBlocking {
        authedTestApp { client ->
            // Token signed with a DIFFERENT secret — verifier on the server side will refuse.
            val token = signed(secret = "an-entirely-different-secret-not-the-deploy-one")
            val response = client.get("/api/jobs") {
                header(HttpHeaders.Authorization, "Bearer $token")
            }
            assertEquals(HttpStatusCode.Unauthorized, response.status)
        }
    }

    @Test
    fun `wrong issuer is rejected with 401`() = runBlocking {
        authedTestApp { client ->
            val token = signed(issuer = "some-other-tenant")
            val response = client.get("/api/jobs") {
                header(HttpHeaders.Authorization, "Bearer $token")
            }
            assertEquals(HttpStatusCode.Unauthorized, response.status)
        }
    }

    @Test
    fun `wrong audience is rejected with 401`() = runBlocking {
        authedTestApp { client ->
            val token = signed(audience = "some-other-service")
            val response = client.get("/api/jobs") {
                header(HttpHeaders.Authorization, "Bearer $token")
            }
            assertEquals(HttpStatusCode.Unauthorized, response.status)
        }
    }

    @Test
    fun `expired token is rejected with 401`() = runBlocking {
        authedTestApp { client ->
            // exp = 1 second in the past.
            val token = signed(expiresAt = Date(System.currentTimeMillis() - 1_000))
            val response = client.get("/api/jobs") {
                header(HttpHeaders.Authorization, "Bearer $token")
            }
            assertEquals(HttpStatusCode.Unauthorized, response.status)
        }
    }

    @Test
    fun `token without sub claim is rejected with 401 (no audit attribution)`() = runBlocking {
        authedTestApp { client ->
            val token = signed(subject = null)
            val response = client.get("/api/jobs") {
                header(HttpHeaders.Authorization, "Bearer $token")
            }
            assertEquals(HttpStatusCode.Unauthorized, response.status)
        }
    }

    @Test
    fun `BasicAuth still works when JWT is also installed (multi-method)`() = runBlocking {
        // The route is wrapped with `authenticate("dashboard", "jwt")` — a request
        // presenting BasicAuth instead of a bearer token must still pass via the
        // "dashboard" basic authenticator.
        authedTestApp { client ->
            val basicToken = Base64.getEncoder().encodeToString("$basicUser:$basicPass".toByteArray())
            val response = client.get("/api/jobs") {
                header(HttpHeaders.Authorization, "Basic $basicToken")
            }
            assertEquals(HttpStatusCode.OK, response.status)
        }
    }

    @Test
    fun `no credentials at all returns 401`() = runBlocking {
        authedTestApp { client ->
            val response = client.get("/api/jobs")
            assertEquals(HttpStatusCode.Unauthorized, response.status)
        }
    }
}
