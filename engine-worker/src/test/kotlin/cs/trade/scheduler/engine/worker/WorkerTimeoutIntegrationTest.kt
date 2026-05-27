@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package cs.trade.scheduler.engine.worker

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import cs.trade.scheduler.core.backend.Scheduler
import cs.trade.scheduler.core.backend.handler.Job
import cs.trade.scheduler.core.backend.handler.JobContext
import cs.trade.scheduler.core.backend.handler.JobHandler
import cs.trade.scheduler.core.backend.handler.JobType
import cs.trade.scheduler.core.backend.handler.retry.FixedDelay
import cs.trade.scheduler.core.backend.schedulerCoreModule
import cs.trade.scheduler.engine.worker.infrastructure.WorkerPool
import cs.trade.scheduler.engine.worker.infrastructure.schedulerWorkerModule
import cs.trade.scheduler.shared.JobState
import cs.trade.scheduler.storage.postgres.domain.repositories.JobRepository
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
        val workerPool = koin.get<WorkerPool>()
        val jobs = koin.get<JobRepository>()

        workerPool.start()

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
