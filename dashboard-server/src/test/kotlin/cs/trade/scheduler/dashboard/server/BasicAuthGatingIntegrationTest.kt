@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package cs.trade.scheduler.dashboard.server

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
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
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
import kotlin.time.Duration.Companion.milliseconds

/**
 * Coverage for the BasicAuth wrap in `standalone-runner/Application.kt::configureKtor`.
 * Four asserts:
 *
 *   1. The BasicAuth mechanism itself rejects requests with no/bad creds — verified via
 *      an inline auth-wrapped probe route (`/auth-probe/api/jobs-like`). This is what
 *      the production wrap is supposed to do.
 *   2. Same probe route returns 200 with correct `Authorization: Basic <base64>`.
 *   3. The `authenticate("dashboard") { configureDashboardRouting() }` wrap now actually
 *      gates `/api/jobs` ⇒ 401 without creds (after the receiver fix — see history note).
 *   4. Health routes are mounted OUTSIDE the auth wrap ⇒ `/health/live` returns 200
 *      without any header (k8s probes don't carry BasicAuth — DESIGN.md 14.6).
 *   5. Same for `/health/leader`.
 *
 * We replicate the auth-wrap shape from `Application.configureKtor` inline rather than
 * importing it because that function is `private` (deliberately — it's the runner's
 * composition root). The shape is one-to-one with what production wires.
 *
 * History: there was a real-prod bug where `configureDashboardRouting()` was a
 * `Routing.()` extension. Kotlin's scope-receiver resolution picks the OUTER
 * `routing { }`'s `Routing` over the `Route` lambda parameter of `authenticate`, so the
 * dashboard routes silently registered at the ROOT level — `/api/jobs` was public. Fixed
 * by changing every `Routing.configureXRouting()` to `Route.configureXRouting()` so the
 * auth lambda's `Route` receiver wins. Test #3 below is the regression pin.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class BasicAuthGatingIntegrationTest {

    private val authUser = "admin"
    private val authPass = "s3cret-test-only"

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
     * Identical to the production auth wrap in `Application.configureKtor`:
     *   - `install(Authentication) { basic("dashboard") { validate {...} } }`
     *   - Health mounted OUTSIDE the wrap so probes don't need credentials.
     *   - `authenticate("dashboard") { configureDashboardRouting() }` wraps REST routes.
     *
     * We keep ContentNegotiation in place because `/api/jobs` GET returns a serialised
     * ListJobsResponse on success — without the plugin we'd get a 500 from the route
     * trying to respond a Kotlin data class.
     */
    private fun authedTestApp(block: suspend (io.ktor.client.HttpClient) -> Unit) = testApplication {
        val leader = LeaderElection(
            jdbcUrl = storage.jdbcUrl,
            jdbcUser = storage.pgUser,
            jdbcPassword = storage.pgPass,
            key = 0x7FFF_FFFF_0003L,
            electionInterval = 5_000.milliseconds,
        )
        val rabbitFactory = ConnectionFactory().apply {
            host = "127.0.0.1"; port = 1; connectionTimeout = 250
            isAutomaticRecoveryEnabled = false
        }
        // Mirror the production `Application.configureKtor` install order EXACTLY: any
        // plugin referenced by routing must already be installed when `routing { ... }`
        // is called. `application { module { ... } }` (vs `application { install(...) }`)
        // is the canonical Ktor 3.x testApplication pattern — Ktor calls our module on
        // application start, before routing builds, so Authentication is in the
        // pluginRegistry by the time `authenticate("dashboard")` looks it up.
        application {
            install(ContentNegotiation) { json() }
            install(io.ktor.server.websocket.WebSockets)
            install(Authentication) {
                basic("dashboard") {
                    realm = "TaskScheduler dashboard"
                    validate { creds ->
                        if (creds.name == authUser && creds.password == authPass) {
                            UserIdPrincipal(creds.name)
                        } else null
                    }
                }
            }
            install(Koin) {
                modules(*DashboardTestSupport.dashboardModules(storage.dataSource, nodeId = "test-auth"))
            }
            routing {
                // Health BEFORE the auth wrap, mirroring production. /health/live must
                // serve 200 without credentials so k8s liveness probes work.
                configureHealthRouting(
                    dataSource = storage.dataSource,
                    rabbitFactory = rabbitFactory,
                    leader = leader,
                )
                // Production wrap. After the receiver fix (see class KDoc), routes
                // declared inside this block are nested under the BasicAuth interceptor
                // and reject requests without `Authorization: Basic …`.
                authenticate("dashboard") {
                    configureDashboardRouting()
                }
                // Inline probe route — same shape as the dashboard routes above. Kept as
                // a minimal control to isolate the BasicAuth plugin from any future
                // breakage in the dashboard sub-routings.
                authenticate("dashboard") {
                    get("/auth-probe/ping") {
                        call.respondText("authed-ok")
                    }
                }
            }
        }
        startApplication()
        block(this.client)
    }

    @Test
    fun `unauthenticated GET on auth-probe ping returns 401`() = runBlocking {
        authedTestApp { client ->
            val response = client.get("/auth-probe/ping")
            // The probe route is registered with the correct `Route.()` receiver inside
            // `authenticate("dashboard") { get(...) { ... } }`, so the BasicAuth
            // interceptor runs and rejects requests that don't carry credentials.
            assertEquals(
                HttpStatusCode.Unauthorized, response.status,
                "expected 401 without creds, got ${response.status} — body: ${response.bodyAsText()}",
            )
        }
    }

    @Test
    fun `authenticated GET on auth-probe ping returns 200`() = runBlocking {
        authedTestApp { client ->
            val authToken = Base64.getEncoder().encodeToString("$authUser:$authPass".toByteArray())
            val response = client.get("/auth-probe/ping") {
                header(HttpHeaders.Authorization, "Basic $authToken")
            }
            // Correct creds: principal is bound, interceptor passes the request through
            // to our handler, body comes back as `authed-ok`.
            assertEquals(
                HttpStatusCode.OK, response.status,
                "valid creds must let the request reach the probe handler — got: ${response.bodyAsText()}",
            )
            assertEquals("authed-ok", response.bodyAsText())
        }
    }

    @Test
    fun `regression — configureDashboardRouting wrap gates api jobs (Route receiver fix)`() = runBlocking {
        authedTestApp { client ->
            // Regression pin for the receiver fix: every `configureXRouting()` is now a
            // `Route.()` extension, so it actually attaches inside the `authenticate`
            // lambda. Unauthenticated `GET /api/jobs` must be rejected by the BasicAuth
            // interceptor before reaching the handler.
            val response = client.get("/api/jobs")
            assertEquals(
                HttpStatusCode.Unauthorized, response.status,
                "auth wrap must gate /api/jobs; if this flips to 200, the route receiver " +
                    "has regressed to Routing.() and the auth wrap is silently bypassed",
            )
        }
    }

    @Test
    fun `authenticated GET on api jobs reaches the handler and returns 200`() = runBlocking {
        // Positive sibling to the regression test — proves the auth gate isn't simply
        // blocking everything. With valid creds, the request passes the interceptor and
        // the JobsRouting handler responds 200 (empty list against a fresh DB is fine).
        authedTestApp { client ->
            val authToken = Base64.getEncoder().encodeToString("$authUser:$authPass".toByteArray())
            val response = client.get("/api/jobs") {
                header(HttpHeaders.Authorization, "Basic $authToken")
            }
            assertEquals(
                HttpStatusCode.OK, response.status,
                "valid creds must let the request through to JobsRouting — body: ${response.bodyAsText()}",
            )
        }
    }

    @Test
    fun `health endpoints stay public — GET health-live returns 200 without credentials`() = runBlocking {
        authedTestApp { client ->
            // /health/live is the simplest probe — it doesn't even touch the DB. If
            // it 401s, k8s sees the pod as down and starts cycling it. This assertion
            // is the load-bearing guarantee for the entire auth wrap design.
            val response = client.get("/health/live")
            assertEquals(HttpStatusCode.OK, response.status)
            assertEquals("ok", response.bodyAsText())
        }
    }

    @Test
    fun `health endpoints stay public — GET health-leader returns 200 without credentials`() = runBlocking {
        // /health/leader has a JSON body — extra check that the public mount didn't
        // accidentally lose ContentNegotiation or the Authentication wrap (since
        // health is OUTSIDE `authenticate("dashboard")`). Status alone wouldn't catch
        // a 401 from a misconfiguration that re-wraps health in auth.
        authedTestApp { client ->
            val response = client.get("/health/leader")
            assertEquals(HttpStatusCode.OK, response.status)
            // Body is `{"leader":<bool>}` — we don't assert which boolean (depends on
            // whether our dummy elector happened to acquire), only that it's well-formed.
            val body = response.bodyAsText()
            assert(body.startsWith("""{"leader":""")) { "body should be {\"leader\":...} — got: $body" }
        }
    }
}
