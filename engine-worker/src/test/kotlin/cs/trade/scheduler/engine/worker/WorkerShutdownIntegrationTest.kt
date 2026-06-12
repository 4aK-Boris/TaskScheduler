@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package cs.trade.scheduler.engine.worker

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import cs.trade.scheduler.core.backend.Scheduler
import cs.trade.scheduler.core.backend.handler.Job
import cs.trade.scheduler.core.backend.handler.JobContext
import cs.trade.scheduler.core.backend.handler.JobHandler
import cs.trade.scheduler.core.backend.handler.JobType
import cs.trade.scheduler.core.backend.schedulerCoreModule
import cs.trade.scheduler.engine.worker.infrastructure.WorkerPool
import cs.trade.scheduler.engine.worker.infrastructure.schedulerWorkerModule
import cs.trade.scheduler.shared.JobState
import cs.trade.scheduler.storage.postgres.domain.repositories.JobRepository
import cs.trade.scheduler.storage.postgres.domain.repositories.WorkerRepository
import cs.trade.scheduler.storage.postgres.infrastructure.schedulerPostgresModule
import cs.trade.scheduler.transport.rabbit.domain.ConsumerHandle
import cs.trade.scheduler.transport.rabbit.domain.JobTransport
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
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
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.measureTime
import kotlin.uuid.Uuid

/**
 * Verifies the two-phase graceful shutdown of [WorkerPool.stop] (regression for the
 * 2026-06-12 prod findings):
 *
 *  1. An IDLE stop must return promptly — the old implementation waited on the internal
 *     scope's children, which include eternal service loops (heartbeat / registry /
 *     cancel-listener), so every shutdown burned the full `shutdownTimeout` even with
 *     zero jobs running.
 *  2. An in-flight handler must be allowed to FINISH during the drain, not be cancelled —
 *     the old implementation called `ConsumerHandle.cancel()` up front, which cancelled
 *     the per-consumer scope and killed running handlers immediately.
 *  3. The node's `worker` registry row must be gone after stop() (and must not be
 *     resurrected by WorkerRegistryLoop — delete happens after the loops are cancelled).
 *
 * No Rabbit container — [TwoPhaseInMemoryTransport] mimics the Rabbit impl's shutdown
 * semantics: `stopDeliveries()` only stops new deliveries; `cancel()` cancels the
 * per-consumer scope (killing whatever still runs in it).
 */
// Top-level so DefaultScheduler.enqueue's `qualifiedName` matches Class.forName in
// WorkerPool.decodePayload (nested classes would mismatch `$` vs `.`).
@Serializable
data class DrainJob(val tag: String) : Job

@JobType(DrainJob::class)
class DrainHandler(
    val started: CompletableDeferred<Unit>,
    val finished: AtomicBoolean,
) : JobHandler<DrainJob> {
    override suspend fun execute(ctx: JobContext, job: DrainJob) {
        started.complete(Unit)
        // Long enough that stop() reliably begins while we're still running; short enough
        // that the drain (and the test) stays fast. delay() is cancellable — if stop()
        // wrongly cancels us, `finished` stays false and the job never reaches SUCCEEDED.
        delay(700.milliseconds)
        finished.set(true)
    }
}

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class WorkerShutdownIntegrationTest {

    private companion object {
        private val externalUrl: String? = System.getenv("EXTERNAL_PG_URL")?.takeIf { it.isNotBlank() }
        private const val NODE_ID = "test-shutdown"
        private val SHUTDOWN_TIMEOUT = 10.seconds
    }

    private lateinit var dataSource: HikariDataSource
    private lateinit var transport: TwoPhaseInMemoryTransport
    private lateinit var handlerStarted: CompletableDeferred<Unit>
    private lateinit var handlerFinished: AtomicBoolean
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

        transport = TwoPhaseInMemoryTransport()
        handlerStarted = CompletableDeferred()
        handlerFinished = AtomicBoolean(false)
        val handlerModule = module {
            single { DrainHandler(handlerStarted, handlerFinished) } bind JobHandler::class
        }
        val fakeTransportModule = module {
            single<JobTransport> { transport }
        }

        startKoin {
            modules(
                schedulerCoreModule {
                    nodeId = NODE_ID
                    defaultJobTimeout = 30.seconds
                },
                schedulerPostgresModule {
                    this.dataSource = this@WorkerShutdownIntegrationTest.dataSource
                    runMigrations = true
                },
                fakeTransportModule,
                schedulerWorkerModule {
                    nodeId = NODE_ID
                    lockDuration = 30.seconds
                    shutdownTimeout = SHUTDOWN_TIMEOUT
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
    fun `idle stop returns promptly and deletes the worker registry row`() = runBlocking {
        val koin = GlobalContext.get()
        val workerPool = koin.get<WorkerPool>()
        val workers = koin.get<WorkerRepository>()

        workerPool.start()
        // WorkerRegistryLoop upserts on its first tick (immediately after launch).
        val row = withTimeoutOrNull(5.seconds) {
            while (workers.findByNodeId(NODE_ID) == null) delay(50.milliseconds)
            workers.findByNodeId(NODE_ID)
        }
        assertNotNull(row, "registry row should appear after start()")

        val elapsed = measureTime { workerPool.stop() }

        // Old behavior: the drain waited on eternal loop children → always the full
        // shutdownTimeout (10s here). New behavior: one drain poll + teardown, well under 3s.
        assertTrue(
            elapsed < 3.seconds,
            "idle stop must not burn the shutdown grace waiting on service loops (took $elapsed)",
        )
        assertNull(workers.findByNodeId(NODE_ID), "graceful stop must delete the registry row")
    }

    @Test
    fun `stop drains an in-flight handler instead of cancelling it`() = runBlocking {
        val koin = GlobalContext.get()
        val workerPool = koin.get<WorkerPool>()
        val workers = koin.get<WorkerRepository>()
        val scheduler = koin.get<Scheduler>()
        val jobs = koin.get<JobRepository>()

        workerPool.start()
        val jobId = scheduler.enqueue(DrainJob(tag = "drain"))
        transport.deliver(jobId)
        withTimeoutOrNull(5.seconds) { handlerStarted.await() }
            ?: error("handler never started — delivery wiring broken")

        val elapsed = measureTime { workerPool.stop() }

        assertTrue(
            handlerFinished.get(),
            "in-flight handler must run to completion during the drain, not be cancelled",
        )
        // Drain ends as soon as the in-flight counter hits zero — nowhere near the 10s grace.
        assertTrue(elapsed < 5.seconds, "drain should end right after the handler finishes (took $elapsed)")

        val finalState = withTimeoutOrNull(5.seconds) {
            var snap = jobs.findById(jobId)
            while (snap?.state != JobState.SUCCEEDED) {
                delay(25.milliseconds)
                snap = jobs.findById(jobId)
            }
            snap
        }
        assertNotNull(finalState, "drained job must finalize as SUCCEEDED")
        assertEquals(JobState.SUCCEEDED, finalState!!.state)

        assertNull(workers.findByNodeId(NODE_ID), "graceful stop must delete the registry row")
    }
}

/**
 * In-memory transport whose [ConsumerHandle] mirrors the Rabbit impl's two-phase shutdown:
 * `stopDeliveries()` only blocks NEW deliveries (handler scope stays alive), `cancel()`
 * cancels the per-consumer scope — anything still running there dies with
 * CancellationException, exactly like `ConsumerHandleImpl.cancel()` killing in-flight
 * handlers. A regression to "cancel everything up front" therefore fails the drain test.
 */
private class TwoPhaseInMemoryTransport : JobTransport {

    private inner class Handle(
        val queue: String,
        val handler: suspend (Uuid) -> Unit,
    ) : ConsumerHandle {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        @Volatile
        var deliveriesStopped = false

        override suspend fun stopDeliveries() {
            deliveriesStopped = true
        }

        override suspend fun cancel() {
            deliveriesStopped = true
            scope.cancel()
        }
    }

    private val handles = ConcurrentHashMap<String, Handle>()

    override suspend fun publish(jobId: Uuid, routingKey: String, priority: Int, delayMillis: Long) {
        // No-op: tests call deliver() explicitly.
    }

    override suspend fun consume(
        queue: String,
        prefetch: Int,
        handler: suspend (jobId: Uuid) -> Unit,
    ): ConsumerHandle = Handle(queue, handler).also { handles[queue] = it }

    override suspend fun cancelAllConsumers() {
        handles.values.forEach { it.cancel() }
        handles.clear()
    }

    override suspend fun close() {
        cancelAllConsumers()
    }

    fun deliver(jobId: Uuid, queue: String = "default") {
        val handle = handles[queue] ?: error("No consumer registered for queue=$queue")
        check(!handle.deliveriesStopped) { "delivering to a quiesced consumer" }
        handle.scope.launch { runCatching { handle.handler(jobId) } }
    }
}
