package cs.trade.scheduler.dashboard.server

import com.rabbitmq.client.ConnectionFactory
import com.zaxxer.hikari.HikariDataSource
import cs.trade.scheduler.engine.infra.infrastructure.leader.LeaderElection
import cs.trade.scheduler.runner.configureHealthRouting
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import kotlin.system.measureTimeMillis
import kotlin.time.Duration.Companion.milliseconds

/**
 * Focused coverage for `standalone-runner`'s [Routing.configureHealthRouting] (DESIGN.md
 * 14.6 — k8s readiness/liveness/leader probes). We do NOT boot the full scheduler stack:
 *
 *   - `/health/ready` only needs a real DataSource and a ConnectionFactory we can point
 *     at an unreachable broker. We use the EXTERNAL_PG_URL DataSource for the DB side
 *     (so `isValid(1)` returns true) and a ConnectionFactory pointed at `127.0.0.1:1`
 *     with a 250ms connection timeout for the Rabbit side. With Rabbit unreachable, the
 *     route's `withTimeout(2.seconds)` MUST fire and return 503 with `rabbit:false`.
 *
 *   - `/health/leader` exercises a real [LeaderElection] against the same DB. Once the
 *     elector acquires the advisory lock, the route reports `{"leader":true}`. We don't
 *     test the follower side here — `LeaderElectionFailoverTest` already covers that —
 *     this is purely about wiring the role indicator through HTTP.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class HealthRoutingIntegrationTest {

    private lateinit var storage: DashboardTestSupport.Storage
    private lateinit var dataSource: HikariDataSource

    @BeforeAll
    fun setUp() {
        storage = DashboardTestSupport.startStorage()
        dataSource = storage.dataSource
    }

    @AfterAll
    fun tearDown() {
        storage.shutdown()
    }

    /**
     * Build a ConnectionFactory that points at a guaranteed-unreachable host:port and
     * sets aggressive timeouts. We use `127.0.0.1:1` (port 1 = `tcpmux`, typically
     * unbound) rather than `nonexistent.invalid` because DNS resolution on some Windows
     * dev boxes hangs for >10s on `.invalid` — `127.0.0.1:1` fails fast with
     * "Connection refused" instead.
     *
     * `connectionTimeout = 250ms` caps the TCP SYN wait at the factory level; the
     * route's own `withTimeout(2.seconds)` is the upper bound regardless. We want
     * BOTH: the factory timeout to make the test snappy, the withTimeout to be the
     * hard ceiling that the test asserts on.
     */
    private fun unreachableRabbitFactory(): ConnectionFactory = ConnectionFactory().apply {
        host = "127.0.0.1"
        port = 1
        connectionTimeout = 250
        // Disable automatic recovery so the factory doesn't spin background reconnect
        // attempts that interfere with the timeout-based assertion below.
        isAutomaticRecoveryEnabled = false
    }

    @Test
    fun `GET health-ready returns 503 with rabbit-false when broker unreachable, db-true preserved`() = runBlocking {
        // Tiny no-op LeaderElection just to satisfy the routing signature — /health/ready
        // doesn't consult it.
        val leader = LeaderElection(
            jdbcUrl = storage.jdbcUrl,
            jdbcUser = storage.pgUser,
            jdbcPassword = storage.pgPass,
            key = 0x7FFF_FFFF_0001L,
            electionInterval = 5_000.milliseconds,
        )
        testApplication {
            application {
                routing {
                    configureHealthRouting(
                        dataSource = dataSource,
                        rabbitFactory = unreachableRabbitFactory(),
                        leader = leader,
                    )
                }
            }

            val elapsed: Long
            val responseStatus: HttpStatusCode
            val body: String
            // Measure how long the handler took — must complete inside ~2.5s because the
            // rabbit ping is `withTimeout(2.seconds)`. If it took longer we'd know the
            // timeout wasn't firing (handler stuck on socket connect) and the probe
            // wouldn't be SLA-friendly for k8s readiness (default initialDelaySeconds=0,
            // periodSeconds=10 — anything over ~2.5s on a single probe would intermittently
            // miss the window).
            val resp = run {
                var r: io.ktor.client.statement.HttpResponse? = null
                elapsed = measureTimeMillis { r = client.get("/health/ready") }
                r!!
            }
            responseStatus = resp.status
            body = resp.bodyAsText()

            assertEquals(HttpStatusCode.ServiceUnavailable, responseStatus, "rabbit-down ⇒ 503 per HealthRouting")
            assertTrue(
                body.contains("\"db\":true"),
                "DB ping must have succeeded against the real Postgres — body was: $body",
            )
            assertTrue(
                body.contains("\"rabbit\":false"),
                "rabbit ping to 127.0.0.1:1 must report false — body was: $body",
            )
            assertTrue(
                elapsed < 3_500,
                "rabbit ping must complete inside the 2s withTimeout (gave 3.5s slack for Ktor + CI jitter) — took ${elapsed}ms",
            )
        }
    }

    @Test
    fun `GET health-leader returns 200 with leader-true once advisory lock acquired`() = runBlocking {
        // Use a key far from production's LEADER_LOCK_KEY so a parallel scheduler-infra
        // process against the same DB doesn't fight us for it. 250ms election interval
        // lets the acquire happen well before the test's `awaitLeader` deadline.
        val leader = LeaderElection(
            jdbcUrl = storage.jdbcUrl,
            jdbcUser = storage.pgUser,
            jdbcPassword = storage.pgPass,
            key = 0x7FFF_FFFF_0002L,
            electionInterval = 250.milliseconds,
        )
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            leader.start(scope)

            // Wait up to 2s for the lock — same bound as LeaderElectionFailoverTest. On a
            // healthy local PG this lands in ~250ms (one election tick).
            val acquired = withTimeoutOrNull(2_000.milliseconds) {
                while (!leader.isCurrentLeader()) delay(50.milliseconds)
                true
            } == true
            assertTrue(acquired, "leader election must acquire within 2s on a healthy PG")

            testApplication {
                application {
                    routing {
                        configureHealthRouting(
                            dataSource = dataSource,
                            // Doesn't matter for /health/leader — only /health/ready probes Rabbit.
                            rabbitFactory = unreachableRabbitFactory(),
                            leader = leader,
                        )
                    }
                }

                val resp = client.get("/health/leader")
                // /health/leader is always 200 — both leader and follower are healthy
                // role indicators. The body tells the caller which one we are.
                assertEquals(HttpStatusCode.OK, resp.status)
                val body = resp.bodyAsText()
                assertEquals(
                    """{"leader":true}""", body,
                    "elector currently holds the lock — endpoint must echo isCurrentLeader()=true",
                )
            }
        } finally {
            // Drop the lock so a follow-up test or parallel infra process doesn't have
            // to wait for connection close to release it.
            runCatching { leader.release() }
            scope.cancel()
        }
    }
}
