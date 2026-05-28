@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package cs.trade.scheduler.dashboard.server

import cs.trade.scheduler.dashboard.server.api.routes.configureDashboardRouting
import cs.trade.scheduler.shared.JobPriority
import cs.trade.scheduler.shared.JobState
import cs.trade.scheduler.shared.dto.TypeStatsResponse
import cs.trade.scheduler.storage.postgres.domain.models.Job
import cs.trade.scheduler.storage.postgres.domain.repositories.JobRepository
import cs.trade.scheduler.storage.postgres.infrastructure.tables.JobTable
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import java.time.ZoneOffset
import kotlin.time.Clock
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant
import kotlin.time.toJavaInstant
import kotlin.uuid.Uuid
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.transactions.suspendTransaction
import org.jetbrains.exposed.v1.jdbc.update
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.koin.ktor.plugin.Koin

/**
 * End-to-end REST coverage for `GET /api/stats/types` (DESIGN.md 22.4).
 *
 * Drives the full stack: Ktor `testApplication` → StatsRouting → GetTypesStatsUseCase
 * → JobRepository.statsByPayloadType → real Postgres. The aggregate SQL (FILTER +
 * percentile_cont) is the bit most likely to break across PG versions, so the table
 * cases below explicitly cover every aggregate column.
 *
 * Per-test isolation: each `@BeforeEach` TRUNCATEs the job table so we can compare
 * counts exactly. We deliberately don't share a payload type across tests — picking a
 * unique name per case gives a defence-in-depth signal if TRUNCATE ever falls behind.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class StatsTypesRoutingIntegrationTest {

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

    @BeforeEach
    fun cleanJobs() {
        // Clean slate per test — the aggregate is a global GROUP BY, so any leftover row
        // from another test would skew the assertions. TRUNCATE cascades through the FKs
        // (job_event, job_dependency, outbox) so we don't have to worry about orphans.
        // Raw JDBC bypasses Exposed entirely so we don't depend on a Database having been
        // connected yet (the first test runs before testApplication has booted Koin).
        storage.dataSource.connection.use { c ->
            c.createStatement().use { st ->
                st.execute("TRUNCATE job, job_event, job_dependency, outbox RESTART IDENTITY CASCADE")
            }
        }
    }

    private fun statsApp(block: suspend (io.ktor.client.HttpClient) -> Unit) = testApplication {
        application {
            install(ContentNegotiation) { json() }
            install(io.ktor.server.websocket.WebSockets)
            install(Koin) {
                modules(*DashboardTestSupport.dashboardModules(storage.dataSource, nodeId = "test-typestats"))
            }
            routing { configureDashboardRouting() }
        }
        startApplication()
        block(this.client)
    }

    @Test
    fun `empty DB returns 200 with empty items`() = runBlocking {
        statsApp { client ->
            val resp = client.get("/api/stats/types")
            assertEquals(HttpStatusCode.OK, resp.status)
            val body: TypeStatsResponse = json.decodeFromString(resp.bodyAsText())
            assertTrue(body.items.isEmpty(), "no jobs → empty items list")
            assertEquals(24, body.rangeHours, "default range when none specified is 24h")
        }
    }

    @Test
    fun `aggregates success and failure counts plus duration min max avg`() = runBlocking {
        statsApp { client ->
            val jobs: JobRepository = DashboardTestSupport.resolve()
            val database: Database = DashboardTestSupport.resolve()

            // Three SUCCEEDED rows of type Mailer with [100, 200, 300]ms durations.
            insertTerminal(jobs, database, "Mailer", JobState.SUCCEEDED, durationMs = 100, attempts = 1)
            insertTerminal(jobs, database, "Mailer", JobState.SUCCEEDED, durationMs = 200, attempts = 1)
            insertTerminal(jobs, database, "Mailer", JobState.SUCCEEDED, durationMs = 300, attempts = 1)
            // One FAILED, with 2 attempts → contributes 1 to retryCount.
            insertTerminal(jobs, database, "Mailer", JobState.FAILED, durationMs = 500, attempts = 2)

            val resp = client.get("/api/stats/types")
            assertEquals(HttpStatusCode.OK, resp.status)
            val body: TypeStatsResponse = json.decodeFromString(resp.bodyAsText())
            assertEquals(1, body.items.size, "single (payloadType, queue) bucket")
            val row = body.items.single()
            assertEquals("Mailer", row.payloadType)
            assertEquals("default", row.queue)
            assertEquals(3L, row.successCount)
            assertEquals(1L, row.failedCount)
            assertEquals(0L, row.cancelledCount)
            // retryCount = sum(max(attempts - 1, 0)) over SUCCEEDED + FAILED. The three
            // SUCCEEDED rows each contributed 0 (attempts=1), the FAILED row contributed
            // attempts-1 = 1. Total = 1.
            assertEquals(1L, row.retryCount)
            // Aggregate min/max/avg are over all four rows (avg = (100+200+300+500)/4 = 275).
            assertEquals(275L, row.avgDurationMs)
            assertEquals(100L, row.minDurationMs)
            assertEquals(500L, row.maxDurationMs)
            assertNotNull(row.p95DurationMs, "p95 must be populated when duration_ms is set")
        }
    }

    @Test
    fun `two distinct payload types return two entries sorted by successCount DESC`() = runBlocking {
        statsApp { client ->
            val jobs: JobRepository = DashboardTestSupport.resolve()
            val database: Database = DashboardTestSupport.resolve()

            // Mailer: 1 SUCCEEDED.
            insertTerminal(jobs, database, "Mailer", JobState.SUCCEEDED, durationMs = 50, attempts = 1)
            // Indexer: 3 SUCCEEDED — should sort first.
            repeat(3) {
                insertTerminal(jobs, database, "Indexer", JobState.SUCCEEDED, durationMs = 10L, attempts = 1)
            }

            val resp = client.get("/api/stats/types")
            assertEquals(HttpStatusCode.OK, resp.status)
            val body: TypeStatsResponse = json.decodeFromString(resp.bodyAsText())
            assertEquals(2, body.items.size)
            // Sort order is successCount DESC — Indexer (3) before Mailer (1).
            assertEquals("Indexer", body.items[0].payloadType)
            assertEquals(3L, body.items[0].successCount)
            assertEquals("Mailer", body.items[1].payloadType)
            assertEquals(1L, body.items[1].successCount)
        }
    }

    @Test
    fun `range=1h excludes rows updated more than 1h ago`() = runBlocking {
        statsApp { client ->
            val jobs: JobRepository = DashboardTestSupport.resolve()
            val database: Database = DashboardTestSupport.resolve()
            val now = Clock.System.now()

            // Fresh row (now) — should be in the 1h bucket.
            insertTerminal(
                jobs, database, "Reporter", JobState.SUCCEEDED,
                durationMs = 42, attempts = 1, updatedAt = now,
            )
            // Stale row (2h ago) — should be excluded by range=1h.
            insertTerminal(
                jobs, database, "Reporter", JobState.SUCCEEDED,
                durationMs = 999, attempts = 1, updatedAt = now - 2.hours,
            )

            val resp = client.get("/api/stats/types?range=1h")
            assertEquals(HttpStatusCode.OK, resp.status)
            val body: TypeStatsResponse = json.decodeFromString(resp.bodyAsText())
            assertEquals(1, body.rangeHours)
            assertEquals(1, body.items.size, "stale row must drop out of the 1h window")
            val row = body.items.single()
            assertEquals(1L, row.successCount, "only the fresh row counts")
            // Aggregates collapse to the fresh row's value — if the stale one leaked in
            // we'd see 999 here instead.
            assertEquals(42L, row.avgDurationMs)
            assertEquals(42L, row.minDurationMs)
            assertEquals(42L, row.maxDurationMs)

            // Sanity-check the wider window still sees both rows.
            val respWide = client.get("/api/stats/types?range=24h")
            val bodyWide: TypeStatsResponse = json.decodeFromString(respWide.bodyAsText())
            assertEquals(24, bodyWide.rangeHours)
            assertEquals(1, bodyWide.items.size, "still grouped under one (type, queue)")
            assertEquals(2L, bodyWide.items.single().successCount, "both rows in the 24h window")
        }
    }

    /**
     * Insert a terminal-state job and force-set `duration_ms`, `attempts`, and
     * `updated_at` to the requested values. `JobRepository.insert` always stamps
     * `updated_at = Clock.now()`, so we follow it with a direct UPDATE — same pattern
     * as `RetentionIntegrationTest.backdateJobUpdatedAt`.
     */
    private suspend fun insertTerminal(
        jobs: JobRepository,
        database: Database,
        payloadType: String,
        state: JobState,
        durationMs: Long,
        attempts: Int,
        updatedAt: Instant = Clock.System.now(),
    ): Uuid {
        val jobId = Uuid.random()
        jobs.insert(
            Job(
                id = jobId,
                state = state,
                queue = "default",
                priority = JobPriority(0),
                payloadType = payloadType,
                payloadJson = "{}",
                scheduledAt = null,
                attempts = attempts,
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
                durationMs = durationMs,
                cancelRequestedAt = null,
                cancelRequestedBy = null,
                contextJson = null,
                createdAt = updatedAt,
                updatedAt = updatedAt,
            ),
        )
        val odt = updatedAt.toJavaInstant().atOffset(ZoneOffset.UTC)
        withContext(Dispatchers.IO) {
            suspendTransaction(db = database) {
                JobTable.update({ JobTable.id eq jobId }) {
                    it[this.updatedAt] = odt
                    it[this.durationMs] = durationMs
                    it[this.attempts] = attempts
                }
            }
        }
        return jobId
    }
}
