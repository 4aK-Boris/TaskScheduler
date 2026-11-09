@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package cs.trade.scheduler.engine.worker

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import cs.trade.scheduler.core.backend.Scheduler
import cs.trade.scheduler.core.backend.EnqueueOptions
import cs.trade.scheduler.core.backend.RecurringDefinition
import cs.trade.scheduler.core.backend.handler.Job
import cs.trade.scheduler.core.backend.handler.JobContext
import cs.trade.scheduler.core.backend.handler.JobHandler
import cs.trade.scheduler.core.backend.handler.JobType
import cs.trade.scheduler.core.backend.handler.retry.FixedDelay
import cs.trade.scheduler.core.backend.schedulerCoreModule
import cs.trade.scheduler.engine.worker.infrastructure.WorkerPool
import cs.trade.scheduler.engine.infra.domain.usecases.FireDueRecurringJobsUseCase
import cs.trade.scheduler.engine.worker.infrastructure.schedulerWorkerModule
import cs.trade.scheduler.shared.JobState
import cs.trade.scheduler.shared.OnFailure
import cs.trade.scheduler.shared.RecurringOverlap
import cs.trade.scheduler.storage.postgres.domain.repositories.JobRepository
import cs.trade.scheduler.storage.postgres.domain.repositories.RecurringJobRepository
import cs.trade.scheduler.storage.postgres.infrastructure.schedulerPostgresModule
import cs.trade.scheduler.transport.rabbit.domain.ConsumerHandle
import cs.trade.scheduler.transport.rabbit.domain.JobTransport
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.koin.core.context.GlobalContext
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.bind
import org.koin.dsl.module
import org.testcontainers.containers.PostgreSQLContainer
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.Uuid

/**
 * Verifies the `withTimeout(defaultJobTimeout)` wrap around `JobHandler.execute`
 * (DESIGN.md 8.2, WorkerPool.processLocked). A handler that hangs past the timeout
 * must be aborted via [kotlinx.coroutines.TimeoutCancellationException] and routed
 * through the regular failure path — retry policy applies as if any other exception
 * had thrown.
 *
 * No Rabbit container — uses [InMemoryJobTransport] that wires `consume` → store
 * handler, `publish` → invoke handler directly (mimics the broker's delivery loop).
 */
// Top-level so DefaultScheduler.enqueue's `qualifiedName` ("...HangingJob") matches what
// WorkerPool.decodePayload feeds into Class.forName. Nested classes would force a `$`
// vs `.` mismatch (Kotlin reflection uses dots, JVM binary name uses dollars).
@Serializable
data class HangingJob(val tag: String) : Job

@JobType(HangingJob::class)
class HangingHandler(
    val invocations: AtomicInteger,
    val finalFailureSignal: CompletableDeferred<Pair<JobContext, Throwable>>,
) : JobHandler<HangingJob> {
    // Hangs at a suspension point — withTimeout aborts at the next delay/yield.
    // We sleep way longer than the test budget so the only way out is the timeout.
    override suspend fun execute(ctx: JobContext, job: HangingJob) {
        invocations.incrementAndGet()
        // 10s far exceeds the configured 250ms job timeout. delay() is cancellable,
        // which is the realistic case — handlers built on coroutine-aware libs.
        delay(10.seconds)
    }

    override suspend fun onFinalFailure(ctx: JobContext, job: HangingJob, error: Throwable) {
        finalFailureSignal.complete(ctx to error)
    }
}

@Serializable
data class DeadlineJob(val runForMillis: Long = 0, val failFirst: Boolean = false) : Job

@JobType(DeadlineJob::class)
class DeadlineHandler(val runs: ConcurrentHashMap<Uuid, AtomicInteger>) : JobHandler<DeadlineJob> {
    override suspend fun execute(ctx: JobContext, job: DeadlineJob) {
        val invocation = runs.computeIfAbsent(ctx.jobId) { AtomicInteger() }.incrementAndGet()
        if (job.failFirst && invocation == 1) error("Retry this job")
        delay(job.runForMillis.milliseconds)
    }
}

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class WorkerTimeoutIntegrationTest {

    private companion object {
        private val externalUrl: String? = System.getenv("EXTERNAL_PG_URL")?.takeIf { it.isNotBlank() }
    }

    private lateinit var dataSource: HikariDataSource
    private lateinit var transport: InMemoryJobTransport
    private lateinit var invocations: AtomicInteger
    private lateinit var finalFailureSignal: CompletableDeferred<Pair<JobContext, Throwable>>
    private var postgres: PostgreSQLContainer<*>? = null
    private val deadlineRuns = ConcurrentHashMap<Uuid, AtomicInteger>()

    @BeforeAll
    fun setUp() {
        runCatching { stopKoin() }
        val jdbcUrl: String; val pgUser: String; val pgPass: String
        if (externalUrl != null) {
            jdbcUrl = externalUrl!!
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

        transport = InMemoryJobTransport()
        invocations = AtomicInteger(0)
        finalFailureSignal = CompletableDeferred()
        val handlerModule = module {
            single { HangingHandler(invocations, finalFailureSignal) } bind JobHandler::class
            single { DeadlineHandler(deadlineRuns) } bind JobHandler::class
        }
        // Override JobTransport with our in-memory fake (Koin last-wins). We never load
        // schedulerRabbitModule — schedulerWorkerModule just needs SOME JobTransport
        // binding via `get<JobTransport>()`.
        val fakeTransportModule = module {
            single<JobTransport> { transport }
        }

        startKoin {
            modules(
                schedulerCoreModule {
                    nodeId = "test-timeout"
                    defaultMaxAttempts = 2
                    // 250ms — long enough for the handler's first delay() to yield,
                    // short enough that the test finishes well under 5s.
                    defaultJobTimeout = 250.milliseconds
                    // Tiny backoff so the retry-then-fail path completes quickly.
                    defaultRetryPolicy = FixedDelay(delay = 50.milliseconds, maxAttempts = 2)
                },
                schedulerPostgresModule {
                    this.dataSource = this@WorkerTimeoutIntegrationTest.dataSource
                    runMigrations = true
                },
                fakeTransportModule,
                schedulerWorkerModule {
                    nodeId = "test-timeout"
                    lockDuration = 30.seconds
                    queue("default", concurrency = 2)
                },
                handlerModule,
            )
        }
        runBlocking { GlobalContext.get().get<WorkerPool>().start() }
    }

    @AfterAll
    fun tearDown() {
        val koin = runCatching { GlobalContext.get() }.getOrNull()
        if (koin != null) {
            runCatching { runBlocking { koin.get<WorkerPool>().stop() } }
        }
        runCatching { stopKoin() }
        runCatching { dataSource.close() }
        runCatching { postgres?.stop() }
    }

    @Test
    fun `handler hanging past defaultJobTimeout is aborted, retried, then FAILED`() = runBlocking {
        val koin = GlobalContext.get()
        val scheduler = koin.get<Scheduler>()
        val jobs = koin.get<JobRepository>()

        val jobId = scheduler.enqueue(HangingJob(tag = "spin"))
        // Bridge: in production the OutboxPublisher loop publishes after enqueue commits.
        // Here we just hand the jobId directly to our fake transport so the worker's
        // consume() callback fires immediately.
        transport.deliver(jobId)

        // After the first delivery times out (250ms + small slack), retry policy schedules
        // attempt #2 via markForRetry + a fresh outbox row. We need to deliver that one
        // too — poll outbox briefly and re-deliver each new row we see.
        // Background pump that keeps re-delivering anything left in outbox.
        val pump = launch(Dispatchers.IO) {
            val seen = mutableSetOf<Uuid>(jobId) // jobId already delivered once above
            // Wait until handler runs once, then keep delivering retry rows.
            while (true) {
                // Sleep so the worker has a chance to handle current delivery & write outbox.
                delay(75.milliseconds)
                val pending = koin.get<cs.trade.scheduler.storage.postgres.domain.repositories.OutboxRepository>()
                    .findUnpublished(limit = 10)
                for (row in pending) {
                    if (row.jobId !in seen) {
                        seen += row.jobId
                    }
                    // markPublished so we don't loop on the same row, then deliver.
                    koin.get<cs.trade.scheduler.storage.postgres.domain.repositories.OutboxRepository>()
                        .markPublished(row.id)
                    transport.deliver(row.jobId)
                }
            }
        }
        try {
            val onFinal = withTimeoutOrNull(8.seconds) { finalFailureSignal.await() }
            assertNotNull(onFinal, "onFinalFailure should fire after maxAttempts=2 timeouts; invocations=${invocations.get()}")
            val (ctx, error) = onFinal!!
            assertEquals(jobId, ctx.jobId)
            assertEquals(2, ctx.attempt, "onFinalFailure must see the last attempt (2)")
            assertTrue(
                error.message?.contains("Timed out") == true || error::class.simpleName?.contains("Timeout") == true,
                "error should be the TimeoutCancellationException (got: $error)",
            )
            assertEquals(2, invocations.get(), "handler should have been invoked exactly maxAttempts=2 times")

            // Final DB state: FAILED, attempts=2.
            val finalState = pollForState(jobs, jobId, JobState.FAILED, timeoutMs = 5_000)
            assertNotNull(finalState, "Job should reach FAILED within 5s")
            assertEquals(JobState.FAILED, finalState!!.state)
            assertEquals(2, finalState.attempts)
        } finally {
            pump.cancel()
        }
    }

    @Test
    fun `expired job skips the handler and resolves all dependency policies`() = runBlocking {

        val koin = GlobalContext.get()
        val scheduler = koin.get<Scheduler>()
        val jobs = koin.get<JobRepository>()

        val parent = scheduler.scheduleAt(
            DeadlineJob(), at = Clock.System.now() - 60.seconds,
            options = EnqueueOptions(maxStartDelay = 10.seconds),
        )
        val cancelledChild = scheduler.enqueueAfter(
            DeadlineJob(), waitFor = listOf(parent),
            options = EnqueueOptions(onParentFailure = OnFailure.CANCEL_CHILD),
        )
        val failedChild = scheduler.enqueueAfter(DeadlineJob(), waitFor = listOf(parent))
        val ignoredChild = scheduler.enqueueAfter(
            DeadlineJob(), waitFor = listOf(parent),
            options = EnqueueOptions(onParentFailure = OnFailure.IGNORE),
        )

        transport.deliver(parent)

        assertNotNull(pollForState(jobs, parent, JobState.CANCELLED, timeoutMs = 5_000))
        assertNotNull(pollForState(jobs, cancelledChild, JobState.CANCELLED, timeoutMs = 5_000))
        assertNotNull(pollForState(jobs, failedChild, JobState.FAILED, timeoutMs = 5_000))
        assertNotNull(pollForState(jobs, ignoredChild, JobState.ENQUEUED, timeoutMs = 5_000))
        assertNull(deadlineRuns[parent])
    }

    @Test
    fun `late job without a deadline and job within allowed delay still run`() = runBlocking {

        val koin = GlobalContext.get()
        val scheduler = koin.get<Scheduler>()
        val jobs = koin.get<JobRepository>()
        val at = kotlin.time.Instant.fromEpochMilliseconds(Clock.System.now().toEpochMilliseconds()) - 60.seconds
        val unlimited = scheduler.scheduleAt(DeadlineJob(), at)
        val withinDelay = scheduler.scheduleAt(
            DeadlineJob(), at, options = EnqueueOptions(maxStartDelay = 120.seconds),
        )

        transport.deliver(unlimited)
        transport.deliver(withinDelay)

        assertNotNull(pollForState(jobs, unlimited, JobState.SUCCEEDED, timeoutMs = 5_000))
        assertNotNull(pollForState(jobs, withinDelay, JobState.SUCCEEDED, timeoutMs = 5_000))
        assertNull(jobs.findById(unlimited)!!.startDeadline)
        assertEquals(at + 120.seconds, jobs.findById(withinDelay)!!.startDeadline)
        assertEquals(1, deadlineRuns[unlimited]!!.get())
        assertEquals(1, deadlineRuns[withinDelay]!!.get())
    }

    @Test
    fun `retry does not extend the deadline and expires before another handler call`() = runBlocking {

        val koin = GlobalContext.get()
        val scheduler = koin.get<Scheduler>()
        val jobs = koin.get<JobRepository>()
        val id = scheduler.enqueue(DeadlineJob(failFirst = true), EnqueueOptions(maxStartDelay = 2.seconds))
        val deadline = jobs.findById(id)!!.startDeadline!!

        transport.deliver(id)
        assertNotNull(pollForState(jobs, id, JobState.AWAITING_RETRY, timeoutMs = 5_000))
        assertEquals(deadline, jobs.findById(id)!!.startDeadline)
        delay((deadline - Clock.System.now()).coerceAtLeast(Duration.ZERO) + 25.milliseconds)
        transport.deliver(id)

        assertNotNull(pollForState(jobs, id, JobState.CANCELLED, timeoutMs = 5_000))
        assertEquals(1, deadlineRuns[id]!!.get())
        assertEquals(deadline, jobs.findById(id)!!.startDeadline)
    }

    @Test
    fun `waiting for dependencies does not reset the deadline`() = runBlocking {

        val koin = GlobalContext.get()
        val scheduler = koin.get<Scheduler>()
        val jobs = koin.get<JobRepository>()
        val parent = scheduler.enqueue(DeadlineJob())
        val child = scheduler.enqueueAfter(
            DeadlineJob(), waitFor = listOf(parent), options = EnqueueOptions(maxStartDelay = Duration.ZERO),
        )
        val deadline = jobs.findById(child)!!.startDeadline

        transport.deliver(parent)
        assertNotNull(pollForState(jobs, child, JobState.ENQUEUED, timeoutMs = 5_000))
        transport.deliver(child)

        assertNotNull(pollForState(jobs, child, JobState.CANCELLED, timeoutMs = 5_000))
        assertEquals(deadline, jobs.findById(child)!!.startDeadline)
        assertNull(deadlineRuns[child])
    }

    @Test
    fun `a handler that started on time can finish after the deadline`() = runBlocking {

        val koin = GlobalContext.get()
        val scheduler = koin.get<Scheduler>()
        val jobs = koin.get<JobRepository>()
        val id = scheduler.enqueue(
            DeadlineJob(runForMillis = 1_500),
            EnqueueOptions(maxStartDelay = 1.seconds, timeout = 3.seconds),
        )

        transport.deliver(id)

        assertNotNull(pollForState(jobs, id, JobState.SUCCEEDED, timeoutMs = 5_000))
        assertEquals(1, deadlineRuns[id]!!.get())
        assertTrue(Clock.System.now() > jobs.findById(id)!!.startDeadline!!)
    }

    @Test
    fun `recurring SKIP resumes after its queued run expires without invoking the stale handler`() = runBlocking {

        val koin = GlobalContext.get()
        val scheduler = koin.get<Scheduler>()
        val jobs = koin.get<JobRepository>()
        val recurring = koin.get<RecurringJobRepository>()
        val fireDue = FireDueRecurringJobsUseCase(
            database = koin.get(), recurringJobs = recurring, jobs = jobs,
            outbox = koin.get(), coreConfig = koin.get(),
        )
        val id = "worker-skip-expiry-${System.nanoTime()}"
        val key = "recurring:$id"
        scheduler.recurring(RecurringDefinition(
            id = id, cron = "* * * * *", job = DeadlineJob(),
            overlap = RecurringOverlap.SKIP, maxStartDelay = 1.seconds,
        ))
        recurring.upsert(recurring.findById(id)!!.copy(nextTriggerAt = Clock.System.now()))
        fireDue().getOrThrow()
        val first = jobs.findLeaderByIdempotencyKey(key)!!

        // Another tick while the first job is still queued must not create a duplicate.
        recurring.upsert(recurring.findById(id)!!.copy(nextTriggerAt = Clock.System.now()))
        fireDue().getOrThrow()
        assertEquals(first.id, jobs.findLeaderByIdempotencyKey(key)!!.id)
        assertEquals(JobState.ENQUEUED, jobs.findById(first.id)!!.state)

        delay((first.startDeadline!! - Clock.System.now()).coerceAtLeast(Duration.ZERO) + 25.milliseconds)
        transport.deliver(first.id)

        assertNotNull(pollForState(jobs, first.id, JobState.CANCELLED, timeoutMs = 5_000))
        assertNull(deadlineRuns[first.id])
        assertNull(jobs.findLeaderByIdempotencyKey(key))

        // Cancellation freed the SKIP leader slot: the next valid cron run can execute.
        recurring.upsert(recurring.findById(id)!!.copy(nextTriggerAt = Clock.System.now()))
        fireDue().getOrThrow()
        val fresh = jobs.findLeaderByIdempotencyKey(key)!!
        transport.deliver(fresh.id)

        assertNotNull(pollForState(jobs, fresh.id, JobState.SUCCEEDED, timeoutMs = 5_000))
        assertEquals(1, deadlineRuns[fresh.id]!!.get())
        assertNull(deadlineRuns[first.id])
    }

    private suspend fun pollForState(
        jobs: JobRepository,
        jobId: Uuid,
        target: JobState,
        timeoutMs: Long,
    ) = withTimeoutOrNull(timeoutMs.milliseconds) {
        var snap = jobs.findById(jobId)
        while (snap?.state != target) {
            delay(25.milliseconds)
            snap = jobs.findById(jobId)
        }
        snap
    }
}

/**
 * In-memory fake. Plays the role of the broker:
 *  - `consume(queue, ...)` stores the worker's per-delivery callback under `queue`.
 *  - `publish(jobId, routingKey, ...)` is a no-op (we don't run the publisher loop).
 *  - `deliver(jobId)` is the test's explicit "broker delivered this message" call — it
 *    looks up the stored callback by routing-equivalent queue ("default") and invokes
 *    it on a fresh coroutine, mimicking a Rabbit consumer thread.
 *
 * Uses a SupervisorJob scope so a failing delivery doesn't tear down siblings.
 */
private class InMemoryJobTransport : JobTransport {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val callbacks = mutableMapOf<String, suspend (Uuid) -> Unit>()

    override suspend fun publish(jobId: Uuid, routingKey: String, priority: Int, delayMillis: Long) {
        // No-op: tests call deliver() explicitly. This silently absorbs any retry-path
        // outbox publish that happens to slip through if the test pump is too slow.
    }

    override suspend fun consume(
        queue: String,
        prefetch: Int,
        handler: suspend (jobId: Uuid) -> Unit,
    ): ConsumerHandle {
        callbacks[queue] = handler
        return object : ConsumerHandle {
            override suspend fun cancel() {
                callbacks.remove(queue)
            }
        }
    }

    override suspend fun cancelAllConsumers() {
        callbacks.clear()
    }

    override suspend fun close() {
        scope.cancel()
    }

    fun deliver(jobId: Uuid, queue: String = "default") {
        val handler = callbacks[queue] ?: error("No consumer registered for queue=$queue")
        // Launch on the scope so the test's runBlocking doesn't block on the long-hanging
        // handler call (which is exactly what we're testing the timeout for).
        scope.launch { runCatching { handler(jobId) } }
    }
}
