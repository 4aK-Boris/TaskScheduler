@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package cs.trade.scheduler.engine.worker

import com.rabbitmq.client.ConnectionFactory
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import cs.trade.scheduler.core.backend.Scheduler
import cs.trade.scheduler.core.backend.handler.Job
import cs.trade.scheduler.core.backend.handler.JobContext
import cs.trade.scheduler.core.backend.handler.JobHandler
import cs.trade.scheduler.core.backend.handler.JobType
import cs.trade.scheduler.core.backend.schedulerCoreModule
import cs.trade.scheduler.engine.infra.domain.usecases.PublishOutboxBatchUseCase
import cs.trade.scheduler.engine.infra.infrastructure.schedulerInfraModule
import cs.trade.scheduler.engine.worker.infrastructure.WorkerPool
import cs.trade.scheduler.engine.worker.infrastructure.schedulerWorkerModule
import cs.trade.scheduler.shared.JobState
import cs.trade.scheduler.storage.postgres.domain.repositories.JobRepository
import cs.trade.scheduler.storage.postgres.infrastructure.schedulerPostgresModule
import cs.trade.scheduler.transport.rabbit.domain.JobTransport
import cs.trade.scheduler.transport.rabbit.infrastructure.schedulerRabbitModule
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertSame
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
import org.testcontainers.containers.RabbitMQContainer
import org.testcontainers.utility.DockerImageName
import java.util.concurrent.Executors
import kotlin.coroutines.ContinuationInterceptor
import kotlin.coroutines.coroutineContext
import kotlin.time.Duration.Companion.seconds

@Serializable
private data class DispatchProbeJob(val n: Long) : Job

/**
 * Capturing handler — records the thread name + the live ContinuationInterceptor inside
 * `execute`. The test assertions read these to prove the custom dispatcher took over
 * (rather than the default `Dispatchers.IO`).
 */
@JobType(DispatchProbeJob::class)
private class DispatchProbeHandler(
    private val captured: CompletableDeferred<Capture>,
) : JobHandler<DispatchProbeJob> {
    data class Capture(val threadName: String, val interceptor: ContinuationInterceptor?)

    override suspend fun execute(ctx: JobContext, job: DispatchProbeJob) {
        captured.complete(
            Capture(
                threadName = Thread.currentThread().name,
                interceptor = coroutineContext[ContinuationInterceptor],
            ),
        )
    }
}

/**
 * Per-queue dispatcher override (DESIGN.md 13.3 / 20.9). When `queue("…", dispatcher = …)`
 * declares an override, the handler body must run on THAT dispatcher — not the default
 * `Dispatchers.IO` inherited from the consumer loop.
 *
 * Strategy: install a single-thread executor with a distinctive thread-name prefix so the
 * test can both:
 *  1. read `Thread.currentThread().name` from inside the handler and confirm it matches
 *     the executor pattern (proves the user code actually ran on that thread), AND
 *  2. assert the `ContinuationInterceptor` element in `coroutineContext` is the SAME
 *     object as the one we passed via `dispatcher = …` (proves the coroutine context
 *     wiring, not just thread coincidence).
 *
 * **PG / Rabbit provisioning.** Same `EXTERNAL_PG_URL` / `EXTERNAL_RABBIT_HOST` pattern
 * as the other engine-worker tests.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CustomDispatcherIntegrationTest {

    private companion object {
        private val rabbitImage: DockerImageName =
            DockerImageName.parse("heidiks/rabbitmq-delayed-message-exchange:3.13.0-management")
                .asCompatibleSubstituteFor("rabbitmq")

        private val externalUrl: String? = System.getenv("EXTERNAL_PG_URL")?.takeIf { it.isNotBlank() }
        private val externalRabbitHost: String? = System.getenv("EXTERNAL_RABBIT_HOST")?.takeIf { it.isNotBlank() }
        private const val DISPATCHER_THREAD_PREFIX = "scheduler-custom-dispatch-probe"
    }

    private lateinit var dataSource: HikariDataSource
    private lateinit var connectionFactory: ConnectionFactory
    private lateinit var captured: CompletableDeferred<DispatchProbeHandler.Capture>
    // Hold onto the dispatcher reference so the test can assert identity later, AND so
    // we can shut down the backing executor in tearDown (otherwise the JVM keeps a live
    // worker thread that prevents clean exit).
    private lateinit var customDispatcher: CoroutineDispatcher
    private val executorService = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "$DISPATCHER_THREAD_PREFIX-1").apply { isDaemon = true }
    }
    private var postgres: PostgreSQLContainer<*>? = null
    private var rabbit: RabbitMQContainer? = null

    @BeforeAll
    fun setUp() {
        runCatching { stopKoin() }

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

        val rabbitHost: String; val rabbitPort: Int; val rabbitUser: String; val rabbitPass: String
        if (externalRabbitHost != null) {
            rabbitHost = externalRabbitHost
            rabbitPort = System.getenv("EXTERNAL_RABBIT_PORT")?.toIntOrNull() ?: 5673
            rabbitUser = System.getenv("EXTERNAL_RABBIT_USER") ?: "scheduler"
            rabbitPass = System.getenv("EXTERNAL_RABBIT_PASSWORD") ?: "scheduler"
        } else {
            val tc = RabbitMQContainer(rabbitImage)
            tc.start()
            rabbit = tc
            rabbitHost = tc.host
            rabbitPort = tc.amqpPort
            rabbitUser = tc.adminUsername
            rabbitPass = tc.adminPassword
        }

        dataSource = HikariDataSource(HikariConfig().apply {
            this.jdbcUrl = jdbcUrl
            username = pgUser
            password = pgPass
            maximumPoolSize = 4
            addDataSourceProperty("stringtype", "unspecified")
        })

        connectionFactory = ConnectionFactory().apply {
            host = rabbitHost
            port = rabbitPort
            username = rabbitUser
            password = rabbitPass
        }

        captured = CompletableDeferred()
        customDispatcher = executorService.asCoroutineDispatcher()
        val handlerModule = module { single { DispatchProbeHandler(captured) } bind JobHandler::class }

        startKoin {
            modules(
                schedulerCoreModule { nodeId = "test-dispatch" },
                schedulerPostgresModule {
                    this.dataSource = this@CustomDispatcherIntegrationTest.dataSource
                    runMigrations = true
                },
                schedulerRabbitModule {
                    connectionFactory = this@CustomDispatcherIntegrationTest.connectionFactory
                    queues = listOf("default")
                },
                schedulerInfraModule(),
                schedulerWorkerModule {
                    nodeId = "test-dispatch"
                    lockDuration = 60.seconds
                    // The dispatcher override: handler.execute runs on customDispatcher's
                    // single thread, not Dispatchers.IO. Concurrency=1 because the
                    // executor only has one thread anyway.
                    queue("default", concurrency = 1, dispatcher = customDispatcher)
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
            runCatching { runBlocking { koin.get<JobTransport>().close() } }
        }
        runCatching { stopKoin() }
        runCatching { dataSource.close() }
        runCatching { rabbit?.stop() }
        runCatching { postgres?.stop() }
        // Shut the executor down explicitly — the asCoroutineDispatcher() wrapper doesn't
        // close it for us, and Executors.newSingleThreadExecutor returns a worker that
        // would otherwise hang the JVM exit.
        runCatching { executorService.shutdownNow() }
    }

    @Test
    fun `handler runs on the queue-configured custom dispatcher, not Dispatchers IO`() = runBlocking {
        val koin = GlobalContext.get()
        val scheduler = koin.get<Scheduler>()
        val workerPool = koin.get<WorkerPool>()
        val publishBatch = koin.get<PublishOutboxBatchUseCase>()
        val jobs = koin.get<JobRepository>()

        workerPool.start()

        val jobId = scheduler.enqueue(DispatchProbeJob(n = 1L))
        assertTrue(publishBatch().getOrThrow() >= 1, "publisher should drain at least our row")

        val capture = withTimeoutOrNull(5.seconds) { captured.await() }
        assertNotNull(capture, "Handler was not invoked within 5s")

        // (1) Thread-name proof: the handler ran on a thread whose name carries our
        // distinctive prefix from the test's single-thread executor.
        assertTrue(
            capture!!.threadName.startsWith(DISPATCHER_THREAD_PREFIX),
            "Expected thread name starting with '$DISPATCHER_THREAD_PREFIX', got '${capture.threadName}'",
        )

        // (2) Identity proof: the ContinuationInterceptor in the handler's coroutine
        // context is the SAME object we passed via `dispatcher = …`. This rules out the
        // edge case where some unrelated dispatcher happens to use a similarly-named
        // thread pool — we know the wiring actually plumbed our override through.
        assertSame(
            customDispatcher,
            capture.interceptor,
            "ContinuationInterceptor must be the configured custom dispatcher",
        )

        // Sanity: the job still finalises to SUCCEEDED via the standard finalize path
        // (which runs on its own coroutine context, NOT the custom dispatcher — we just
        // verified the handler body's dispatcher, not the infrastructure code's).
        val finalState = pollForState(jobs, jobId, JobState.SUCCEEDED, timeoutMs = 5_000)
        assertEquals(JobState.SUCCEEDED, finalState?.state)
    }

    private suspend fun pollForState(
        jobs: JobRepository,
        jobId: kotlin.uuid.Uuid,
        target: JobState,
        timeoutMs: Long,
    ) = withTimeoutOrNull(timeoutMs) {
        var snap = jobs.findById(jobId)
        while (snap?.state != target) {
            delay(50)
            snap = jobs.findById(jobId)
        }
        snap
    }
}
