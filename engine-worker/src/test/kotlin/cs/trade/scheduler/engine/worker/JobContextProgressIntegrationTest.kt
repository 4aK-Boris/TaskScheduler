@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package cs.trade.scheduler.engine.worker

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import cs.trade.scheduler.core.backend.events.EventBus
import cs.trade.scheduler.core.backend.events.InMemoryEventBus
import cs.trade.scheduler.engine.worker.domain.usecases.PropagateRollupProgressUseCase
import cs.trade.scheduler.engine.worker.domain.usecases.ReportProgressUseCase
import cs.trade.scheduler.engine.worker.infrastructure.JobContextImpl
import cs.trade.scheduler.shared.JobPriority
import cs.trade.scheduler.shared.JobState
import cs.trade.scheduler.shared.events.WebSocketEvent
import cs.trade.scheduler.storage.postgres.domain.models.Job as JobModel
import cs.trade.scheduler.storage.postgres.infrastructure.repositories.JobRepositoryImpl
import cs.trade.scheduler.storage.postgres.infrastructure.repositories.JobRollupRepositoryImpl
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.flywaydb.core.Flyway
import org.jetbrains.exposed.v1.jdbc.Database
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.testcontainers.containers.PostgreSQLContainer
import kotlin.time.Clock
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.Uuid

/**
 * End-to-end progress flow at the [JobContextImpl] entry point (handler-facing API):
 *
 *  1. `ctx.updateProgress(0.3f, "loading")` on a PROCESSING row → PG row carries
 *     `progress=0.3, progress_msg="loading", progress_updated_at` and a
 *     [WebSocketEvent.JobProgress] arrives on the [EventBus].
 *  2. Second call within `PROGRESS_MIN_INTERVAL_MS` (1s) is silently dropped — neither
 *     row nor bus see it.
 *  3. After the throttle window elapses the next call lands fresh data.
 *  4. Late report after a terminal transition is a no-op (state-scoped UPDATE in the
 *     repo — see `JobRepositoryImpl.setProgress`).
 *
 * No Rabbit, no Koin, no worker pool — we wire the use-case stack by hand so the
 * assertions only see what this code path does.
 *
 * **PG provisioning.** Honours `EXTERNAL_PG_URL` for the shared `scheduler-test-pg`;
 * falls back to Testcontainers when absent.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class JobContextProgressIntegrationTest {

    private companion object {
        private val externalUrl: String? = System.getenv("EXTERNAL_PG_URL")?.takeIf { it.isNotBlank() }
    }

    private lateinit var dataSource: HikariDataSource
    private lateinit var database: Database
    private lateinit var jobs: JobRepositoryImpl
    private lateinit var eventBus: InMemoryEventBus
    private lateinit var reportProgress: ReportProgressUseCase
    private var postgres: PostgreSQLContainer<*>? = null

    @BeforeAll
    fun setUp() {
        val jdbcUrl: String; val pgUser: String; val pgPass: String
        if (externalUrl != null) {
            jdbcUrl = externalUrl
            pgUser = System.getenv("EXTERNAL_PG_USER") ?: "scheduler"
            pgPass = System.getenv("EXTERNAL_PG_PASSWORD") ?: "scheduler"
        } else {
            val tc = PostgreSQLContainer("postgres:16-alpine")
                .withDatabaseName("scheduler")
                .withUsername("scheduler")
                .withPassword("scheduler")
            tc.start()
            postgres = tc
            jdbcUrl = tc.jdbcUrl
            pgUser = tc.username
            pgPass = tc.password
        }

        dataSource = HikariDataSource(HikariConfig().apply {
            this.jdbcUrl = jdbcUrl
            username = pgUser
            password = pgPass
            maximumPoolSize = 4
            addDataSourceProperty("stringtype", "unspecified")
        })

        Flyway.configure().dataSource(dataSource).locations("classpath:scheduler/migration").load().migrate()

        database = Database.connect(dataSource)
        jobs = JobRepositoryImpl(database)
        eventBus = InMemoryEventBus()
        val rollups = JobRollupRepositoryImpl(database)
        reportProgress = ReportProgressUseCase(
            jobs = jobs,
            eventBus = eventBus,
            propagateRollup = PropagateRollupProgressUseCase(
                jobs = jobs,
                rollups = rollups,
                eventBus = eventBus,
            ),
        )
    }

    @BeforeEach
    fun cleanTables() {
        // Shared scheduler-test-pg: assertions count PG rows and bus emissions by jobId
        // we just inserted. Other suites can leave PROCESSING rows around; truncating
        // here keeps the row counts honest and the test deterministic.
        runCatching {
            dataSource.connection.use { conn ->
                conn.createStatement().use {
                    it.execute("TRUNCATE job, outbox, job_rollup, job_event RESTART IDENTITY CASCADE")
                }
            }
        }
    }

    @AfterAll
    fun tearDown() {
        runCatching { dataSource.close() }
        runCatching { postgres?.stop() }
    }

    @Test
    fun `updateProgress on PROCESSING row writes fields and emits JobProgress event`() = runBlocking {
        val jobId = Uuid.random()
        jobs.insert(processingJob(jobId))

        val collector = startCollector(expected = 1)
        try {
            val ctx = makeCtx(jobId)
            ctx.updateProgress(0.3f, "loading")

            val events = collector.await(2_000)
            assertEquals(1, events.size, "Exactly one JobProgress event expected")
            val ev = events.single() as WebSocketEvent.JobProgress
            assertEquals(jobId.toString(), ev.id)
            assertEquals(0.3f, ev.progress)
            assertEquals("loading", ev.msg)

            val row = jobs.findById(jobId)!!
            assertEquals(0.3f, row.progress)
            assertEquals("loading", row.progressMsg)
            assertNotNull(row.progressUpdatedAt)
        } finally {
            collector.cancel()
        }
    }

    @Test
    fun `second updateProgress within throttle window is dropped — neither row nor bus see it`() = runBlocking {
        val jobId = Uuid.random()
        jobs.insert(processingJob(jobId))

        // Two calls back-to-back; throttle is 1s. We expect ONE event total.
        val collector = startCollector(expected = 1)
        try {
            val ctx = makeCtx(jobId)
            ctx.updateProgress(0.2f, "first")
            ctx.updateProgress(0.5f, "second-should-be-dropped")

            val events = collector.await(2_000)
            // Brief grace in case the throttle were lax — it isn't, but proving negative
            // requires waiting a beat for any rogue emission to arrive.
            delay(150.milliseconds)
            assertEquals(1, events.size, "Throttled second call must not emit a second event")
            assertEquals(0.2f, (events.single() as WebSocketEvent.JobProgress).progress)

            // Row keeps the FIRST value — the second write never reached the repo.
            val row = jobs.findById(jobId)!!
            assertEquals(0.2f, row.progress, "Row must carry the first call's value, not the second's")
            assertEquals("first", row.progressMsg)
        } finally {
            collector.cancel()
        }
    }

    @Test
    fun `after throttle window elapses, next call lands new data`() = runBlocking {
        val jobId = Uuid.random()
        jobs.insert(processingJob(jobId))

        val collector = startCollector(expected = 2)
        try {
            val ctx = makeCtx(jobId)
            ctx.updateProgress(0.1f, "begin")

            // Sleep just past the throttle window so the next call is eligible.
            delay(JobContextImpl.PROGRESS_MIN_INTERVAL_MS.milliseconds + 50.milliseconds)
            ctx.updateProgress(0.9f, "almost done")

            val events = collector.await(3_000)
            assertEquals(2, events.size, "Both calls outside the throttle window must emit")
            val (first, second) = events.map { it as WebSocketEvent.JobProgress }
            assertEquals(0.1f, first.progress)
            assertEquals(0.9f, second.progress)

            val row = jobs.findById(jobId)!!
            assertEquals(0.9f, row.progress, "Row carries the latest value")
            assertEquals("almost done", row.progressMsg)
        } finally {
            collector.cancel()
        }
    }

    @Test
    fun `setProgress on a terminal row is silently dropped — state-scoped UPDATE`() = runBlocking {
        val jobId = Uuid.random()
        // Insert as SUCCEEDED to mirror the race window: handler returns, finalize flips
        // state, a late progress write would otherwise repaint the terminal row.
        jobs.insert(processingJob(jobId).copy(state = JobState.SUCCEEDED))

        val collector = startCollector(expected = 0)
        try {
            // Calling the use-case directly bypasses the JobContextImpl throttle — this
            // checks the repo's state filter, not the client-side throttle.
            val ok = reportProgress(jobId, 0.5f, "late").getOrThrow()
            assertEquals(false, ok, "setProgress on a terminal row must return false")

            // Wait a beat to make sure the (non-)event has every chance to arrive.
            delay(300.milliseconds)
            val events = collector.snapshot()
            assertTrue(events.isEmpty(), "No event must be emitted when the repo write was a no-op")

            val row = jobs.findById(jobId)!!
            assertNull(row.progress, "Terminal row must not pick up progress from a late report")
            assertNull(row.progressMsg)
        } finally {
            collector.cancel()
        }
    }

    private fun makeCtx(jobId: Uuid) = JobContextImpl(
        jobId = jobId,
        attempt = 1,
        queue = "default",
        enqueuedAt = Clock.System.now(),
        maxAttempts = 3,
        parentJobIds = emptyList(),
        jobs = jobs,
        reportProgress = reportProgress,
    )

    private fun processingJob(id: Uuid): JobModel {
        val now = Clock.System.now()
        return JobModel(
            id = id,
            state = JobState.PROCESSING,
            queue = "default",
            priority = JobPriority(0),
            payloadType = "test.ProgressJob",
            payloadJson = """{"_type":"test.ProgressJob"}""",
            scheduledAt = null,
            attempts = 1,
            maxAttempts = 3,
            timeoutSeconds = null,
            lockedBy = "test-worker",
            lockedUntil = now + 60.seconds,
            pendingDeps = 0,
            version = 1,
            idempotencyKey = null,
            targetNode = null,
            targetTag = null,
            progress = null,
            progressMsg = null,
            progressUpdatedAt = null,
            startedAt = now,
            durationMs = null,
            cancelRequestedAt = null,
            cancelRequestedBy = null,
            contextJson = null,
            createdAt = now,
            updatedAt = now,
        )
    }

    /**
     * Subscribes to the bus in a background coroutine and exposes `await(timeoutMs)` /
     * `snapshot()` / `cancel()` so tests can express "wait for N events or give up" without
     * dragging a CoroutineScope through every assertion.
     *
     * Suspends briefly before returning so the collector has time to actually subscribe —
     * SharedFlow with replay=0 drops events that fire before the first collect() lands.
     */
    private suspend fun startCollector(expected: Int): EventCollector {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val received = mutableListOf<WebSocketEvent>()
        val reached = CompletableDeferred<Unit>()
        val job: Job = scope.launch {
            eventBus.events.collect { ev ->
                synchronized(received) {
                    received += ev
                    if (expected > 0 && received.size >= expected && !reached.isCompleted) {
                        reached.complete(Unit)
                    }
                }
            }
        }
        delay(50.milliseconds)
        return EventCollector(scope, job, received, reached)
    }

    private class EventCollector(
        private val scope: CoroutineScope,
        private val job: Job,
        private val received: MutableList<WebSocketEvent>,
        private val reached: CompletableDeferred<Unit>,
    ) {
        suspend fun await(timeoutMs: Long): List<WebSocketEvent> {
            withTimeoutOrNull(timeoutMs) { reached.await() }
            return snapshot()
        }
        fun snapshot(): List<WebSocketEvent> = synchronized(received) { received.toList() }
        fun cancel() {
            job.cancel()
            scope.cancel()
        }
    }
}
