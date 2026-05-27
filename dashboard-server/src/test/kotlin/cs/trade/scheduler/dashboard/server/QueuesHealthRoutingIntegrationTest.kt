@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package cs.trade.scheduler.dashboard.server

import cs.trade.scheduler.dashboard.server.api.routes.configureDashboardRouting
import cs.trade.scheduler.shared.JobPriority
import cs.trade.scheduler.shared.JobState
import cs.trade.scheduler.shared.dto.QueueHealthResponse
import cs.trade.scheduler.shared.dto.QueueHealthStatus
import cs.trade.scheduler.storage.postgres.domain.models.Job
import cs.trade.scheduler.storage.postgres.domain.repositories.JobRepository
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.koin.ktor.plugin.Koin
import kotlin.time.Clock
import kotlin.uuid.Uuid

/**
 * End-to-end REST coverage for `GET /api/queues/health` (DESIGN.md 20.10).
 *
 * Drives the full stack:
 *   Ktor `testApplication` (HTTP layer)
 *     → `QueuesRouting.configureQueuesRouting()` (routing layer)
 *     → `GetQueuesHealthUseCase` (use-case layer, injected by Koin)
 *     → `JobRepository.countActiveByQueue()` (storage layer)
 *     → real Postgres.
 *
 * Why not mock: status-bucket math + DESC sort live in the use case; the GROUP BY +
 * non-terminal filter live in the repository's raw SQL. Both are easy to get subtly wrong
 * (off-by-one on threshold, NULLS ordering, stale terminal rows leaking), so this file
 * pins the wiring end-to-end against a real PG.
 *
 * Lifecycle: manual (no `@Testcontainers`) so `EXTERNAL_PG_URL` can bypass the daemon API.
 * Each test runs `TRUNCATE job, outbox` in `@BeforeEach` so inserts can't bleed across
 * cases on a shared external PG (cf. `SafetyNetIntegrationTest.cleanTables`).
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class QueuesHealthRoutingIntegrationTest {

    private lateinit var storage: DashboardTestSupport.Storage
    private val json = Json { ignoreUnknownKeys = true }

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
     * Truncate the `job` + `outbox` tables between tests. Without this the "empty DB"
     * case fails the moment any previous test leaves a non-terminal row behind, and the
     * "sorted DESC" case starts seeing extra queue names from neighbours.
     *
     * `RESTART IDENTITY CASCADE` mirrors `SafetyNetIntegrationTest.cleanTables`.
     */
    @BeforeEach
    fun cleanTables() {
        runCatching {
            storage.dataSource.connection.use { conn ->
                conn.createStatement().use { it.execute("TRUNCATE job, outbox RESTART IDENTITY CASCADE") }
            }
        }
    }

    /**
     * Boots a minimal Ktor app with only the dashboard routing wired. Caller can override
     * `queueHealthThresholds` so the "custom thresholds" test can run against a tiny
     * (10/50) config without inserting thousands of jobs.
     *
     * `startApplication()` forces config sync (see `RerouteRoutingIntegrationTest` for
     * the rationale around Koin GlobalContext init order). WebSockets is installed
     * because `EventsRouting.kt` mounted by `configureDashboardRouting()` declares
     * `webSocket(...)` — the routing block would `MissingApplicationPluginException`
     * without it even though we never hit /api/ws/events here.
     */
    private fun queuesHealthApp(
        thresholds: QueueHealthThresholds? = null,
        block: suspend (io.ktor.client.HttpClient) -> Unit,
    ) = testApplication {
        application {
            install(ContentNegotiation) { json() }
            install(io.ktor.server.websocket.WebSockets)
            install(Koin) {
                modules(
                    *DashboardTestSupport.dashboardModules(
                        dataSource = storage.dataSource,
                        nodeId = "test-queues-health",
                        queueHealthThresholds = thresholds,
                    ),
                )
            }
            routing { configureDashboardRouting() }
        }
        startApplication()
        block(this.client)
    }

    @Test
    fun `empty DB returns 200 with empty items list`() = runBlocking {
        queuesHealthApp { client ->
            val response = client.get("/api/queues/health")
            assertEquals(HttpStatusCode.OK, response.status)
            val body: QueueHealthResponse = json.decodeFromString(response.bodyAsText())
            assertTrue(
                body.items.isEmpty(),
                "no jobs → no queue entries (GROUP BY over empty rowset yields zero groups)",
            )
        }
    }

    @Test
    fun `single queue below ELEVATED threshold reports NORMAL`() = runBlocking {
        queuesHealthApp { client ->
            val jobs: JobRepository = DashboardTestSupport.resolve()
            // 10 ENQUEUED rows on "default". Default threshold is elevated=1000 so this
            // sits squarely in NORMAL bucket.
            repeat(10) { jobs.insert(sampleJob(queue = "default")) }

            val response = client.get("/api/queues/health")
            assertEquals(HttpStatusCode.OK, response.status)
            val body: QueueHealthResponse = json.decodeFromString(response.bodyAsText())
            assertEquals(1, body.items.size, "exactly one queue has rows")
            val item = body.items.single()
            assertEquals("default", item.queue)
            assertEquals(10L, item.depth)
            assertEquals(QueueHealthStatus.NORMAL, item.status)
        }
    }

    @Test
    fun `multiple queues are returned sorted by depth DESC`() = runBlocking {
        queuesHealthApp { client ->
            val jobs: JobRepository = DashboardTestSupport.resolve()
            // 50 on "default" + 200 on "heavy" — both below the 1000 elevated threshold,
            // so the assertion isolates "sort by depth DESC" from status bucketing.
            repeat(50) { jobs.insert(sampleJob(queue = "default")) }
            repeat(200) { jobs.insert(sampleJob(queue = "heavy")) }

            val response = client.get("/api/queues/health")
            assertEquals(HttpStatusCode.OK, response.status)
            val body: QueueHealthResponse = json.decodeFromString(response.bodyAsText())
            assertEquals(2, body.items.size)
            // heavy first (200 > 50). NORMAL status on both — proves the sort isn't
            // accidentally piggy-backing off status enum ordering.
            assertEquals("heavy", body.items[0].queue)
            assertEquals(200L, body.items[0].depth)
            assertEquals(QueueHealthStatus.NORMAL, body.items[0].status)
            assertEquals("default", body.items[1].queue)
            assertEquals(50L, body.items[1].depth)
            assertEquals(QueueHealthStatus.NORMAL, body.items[1].status)
        }
    }

    @Test
    fun `custom thresholds bucket queues into NORMAL ELEVATED and OVERLOADED`() = runBlocking {
        // Tiny thresholds so the test runs fast: 5 rows = NORMAL (<10), 20 rows =
        // ELEVATED ([10,50)), 60 rows = OVERLOADED (>=50). Threading via the
        // DashboardTestSupport.dashboardModules override added for this test file.
        queuesHealthApp(thresholds = QueueHealthThresholds(elevated = 10, overloaded = 50)) { client ->
            val jobs: JobRepository = DashboardTestSupport.resolve()
            repeat(5) { jobs.insert(sampleJob(queue = "low")) }
            repeat(20) { jobs.insert(sampleJob(queue = "med")) }
            repeat(60) { jobs.insert(sampleJob(queue = "high")) }

            val response = client.get("/api/queues/health")
            assertEquals(HttpStatusCode.OK, response.status)
            val body: QueueHealthResponse = json.decodeFromString(response.bodyAsText())
            assertEquals(3, body.items.size)
            // DESC by depth → high (60), med (20), low (5).
            val byQueue = body.items.associateBy { it.queue }
            assertEquals(60L, byQueue.getValue("high").depth)
            assertEquals(QueueHealthStatus.OVERLOADED, byQueue.getValue("high").status)
            assertEquals(20L, byQueue.getValue("med").depth)
            assertEquals(QueueHealthStatus.ELEVATED, byQueue.getValue("med").status)
            assertEquals(5L, byQueue.getValue("low").depth)
            assertEquals(QueueHealthStatus.NORMAL, byQueue.getValue("low").status)
            // And confirm the DESC order while we're here — UI relies on it for "worst
            // queue surfaces first" without re-sorting client-side.
            assertEquals(listOf("high", "med", "low"), body.items.map { it.queue })
        }
    }

    /**
     * Minimal `Job` row in ENQUEUED state on [queue]. Bypasses `Scheduler.enqueue` so we
     * don't pay the outbox + event-bus + idempotency cost — `countActiveByQueue` only
     * cares about (`state`, `queue`) per the WHERE clause in `JobRepositoryImpl`.
     */
    private fun sampleJob(queue: String, state: JobState = JobState.ENQUEUED): Job {
        val now = Clock.System.now()
        return Job(
            id = Uuid.random(),
            state = state,
            queue = queue,
            priority = JobPriority(0),
            payloadType = "test.QueuesHealth",
            payloadJson = """{"_type":"test.QueuesHealth"}""",
            scheduledAt = null,
            attempts = 0,
            maxAttempts = 3,
            timeoutSeconds = null,
            lockedBy = null,
            lockedUntil = null,
            pendingDeps = 0,
            version = 0,
            idempotencyKey = null,
            targetNode = null,
            targetTag = null,
            progress = null,
            progressMsg = null,
            progressUpdatedAt = null,
            startedAt = null,
            durationMs = null,
            cancelRequestedAt = null,
            cancelRequestedBy = null,
            contextJson = null,
            createdAt = now,
            updatedAt = now,
        )
    }
}
