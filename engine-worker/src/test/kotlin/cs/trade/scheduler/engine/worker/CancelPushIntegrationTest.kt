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
import cs.trade.scheduler.engine.infra.infrastructure.loops.OutboxPublisher
import cs.trade.scheduler.engine.infra.infrastructure.schedulerInfraModule
import cs.trade.scheduler.engine.worker.infrastructure.WorkerPool
import cs.trade.scheduler.engine.worker.infrastructure.schedulerWorkerModule
import cs.trade.scheduler.shared.CancelResult
import cs.trade.scheduler.shared.JobState
import cs.trade.scheduler.storage.postgres.domain.repositories.JobRepository
import cs.trade.scheduler.storage.postgres.infrastructure.schedulerPostgresModule
import cs.trade.scheduler.transport.rabbit.domain.JobTransport
import cs.trade.scheduler.transport.rabbit.infrastructure.schedulerRabbitModule
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
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
import kotlin.time.Duration.Companion.seconds

@Serializable
private data class NonPollingJob(val unused: Int = 0) : Job

@Serializable
private data class StubbornJob(val unused: Int = 0) : Job

/**
 * Per-test signal holders. The handlers are singletons in the shared Koin graph (one
 * `@BeforeAll` startup), but each test needs fresh `CompletableDeferred`s — so the handlers
 * read them through `@Volatile` fields that `@BeforeEach` swaps. Tests run sequentially, so
 * there's no cross-test interleaving on these.
 */
private object CancelPushHooks {
    @Volatile var nonPollingStarted: CompletableDeferred<Unit> = CompletableDeferred()
    @Volatile var nonPollingUnwound: CompletableDeferred<Unit> = CompletableDeferred()
    @Volatile var stubbornStarted: CompletableDeferred<Unit> = CompletableDeferred()
}

/**
 * A handler that NEVER polls [JobContext.isCancellationRequested] — it just suspends in a
 * `delay` loop. Proves the push path (DESIGN.md 22.7): a `job_cancel` NOTIFY cancels the
 * handler coroutine directly, interrupting it at the `delay` suspension without any
 * cooperative polling on the handler's part. The `finally` fires on coroutine cancellation.
 */
@JobType(NonPollingJob::class)
private class NonPollingHandler : JobHandler<NonPollingJob> {
    override suspend fun execute(ctx: JobContext, job: NonPollingJob) {
        CancelPushHooks.nonPollingStarted.complete(Unit)
        try {
            repeat(2_000) { delay(50) }
            error("NonPollingHandler ran to completion — push cancel never interrupted it")
        } finally {
            if (!CancelPushHooks.nonPollingUnwound.isCompleted) CancelPushHooks.nonPollingUnwound.complete(Unit)
        }
    }
}

/**
 * A non-cooperative handler: a blocking loop with no suspension points, so coroutine
 * cancellation can't touch it. The worker must force the row terminal FAILED once the
 * grace period elapses (DESIGN.md 22.7). Bounded so the leaked coroutine eventually
 * returns and the JVM can exit.
 */
@JobType(StubbornJob::class)
private class StubbornHandler : JobHandler<StubbornJob> {
    override suspend fun execute(ctx: JobContext, job: StubbornJob) {
        CancelPushHooks.stubbornStarted.complete(Unit)
        val deadline = System.currentTimeMillis() + STUBBORN_RUN_MS
        while (System.currentTimeMillis() < deadline) {
            @Suppress("BlockingMethodInNonBlockingContext")
            Thread.sleep(50)
        }
    }

    private companion object {
        const val STUBBORN_RUN_MS = 4_000L
    }
}

/**
 * Push-based cancellation of PROCESSING jobs (DESIGN.md 22.7) — the two cases the
 * cooperative-polling [CancelInFlightIntegrationTest] doesn't cover:
 *  1. A handler that only suspends (never polls) is still interrupted by the NOTIFY-driven
 *     coroutine cancel → terminal CANCELLED.
 *  2. A non-cooperative blocking handler is force-killed after [cancelGracePeriod] → FAILED.
 *
 * Same `EXTERNAL_PG_URL` / `EXTERNAL_RABBIT_HOST` provisioning as the sibling tests.
 * `cancelGracePeriod` is shrunk to 1s so the force-kill case runs fast.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CancelPushIntegrationTest {

    private companion object {
        private val rabbitImage: DockerImageName =
            DockerImageName.parse("heidiks/rabbitmq-delayed-message-exchange:3.13.0-management")
                .asCompatibleSubstituteFor("rabbitmq")

        private val externalUrl: String? = System.getenv("EXTERNAL_PG_URL")?.takeIf { it.isNotBlank() }
        private val externalRabbitHost: String? = System.getenv("EXTERNAL_RABBIT_HOST")?.takeIf { it.isNotBlank() }
    }

    private lateinit var dataSource: HikariDataSource
    private lateinit var connectionFactory: ConnectionFactory
    private lateinit var publisherScope: CoroutineScope
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

        val testHandlerModule = module {
            single { NonPollingHandler() } bind JobHandler::class
            single { StubbornHandler() } bind JobHandler::class
        }

        startKoin {
            modules(
                schedulerCoreModule { nodeId = "test-cancel-push" },
                schedulerPostgresModule {
                    this.dataSource = this@CancelPushIntegrationTest.dataSource
                    runMigrations = true
                },
                schedulerRabbitModule {
                    connectionFactory = this@CancelPushIntegrationTest.connectionFactory
                    queues = listOf("default")
                },
                schedulerInfraModule(),
                schedulerWorkerModule {
                    nodeId = "test-cancel-push"
                    lockDuration = 60.seconds
                    cancelGracePeriod = 1.seconds
                    queue("default", concurrency = 4)
                },
                testHandlerModule,
            )
        }

        publisherScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        runBlocking {
            GlobalContext.get().get<WorkerPool>().start()
            GlobalContext.get().get<OutboxPublisher>().start(publisherScope, intervalMillis = 20L)
        }
    }

    @BeforeEach
    fun resetSignals() {
        CancelPushHooks.nonPollingStarted = CompletableDeferred()
        CancelPushHooks.nonPollingUnwound = CompletableDeferred()
        CancelPushHooks.stubbornStarted = CompletableDeferred()
    }

    @AfterAll
    fun tearDown() {
        runCatching { publisherScope.cancel() }
        val koin = runCatching { GlobalContext.get() }.getOrNull()
        if (koin != null) {
            runCatching { runBlocking { koin.get<WorkerPool>().stop() } }
            runCatching { runBlocking { koin.get<JobTransport>().close() } }
        }
        runCatching { stopKoin() }
        runCatching { dataSource.close() }
        runCatching { rabbit?.stop() }
        runCatching { postgres?.stop() }
    }

    @Test
    fun `push cancel interrupts a non-polling handler — ends CANCELLED`() = runBlocking {
        val koin = GlobalContext.get()
        val scheduler = koin.get<Scheduler>()
        val jobs = koin.get<JobRepository>()

        val jobId = scheduler.enqueue(NonPollingJob())

        withTimeoutOrNull(5.seconds) { CancelPushHooks.nonPollingStarted.await() }
            ?: error("NonPollingHandler never started")

        val cancelResult = scheduler.cancel(jobId, by = "test")
        assertEquals(CancelResult.CANCEL_REQUESTED, cancelResult)

        // The handler never polls — the only way it stops is the push cancelling its
        // coroutine at the delay() suspension point.
        withTimeoutOrNull(5.seconds) { CancelPushHooks.nonPollingUnwound.await() }
            ?: error("NonPollingHandler was not interrupted by the push cancel")

        val finalState = pollForState(jobs, jobId, JobState.CANCELLED, timeoutMs = 5_000)
        assertNotNull(finalState, "Job should reach CANCELLED after the push cancel")
        assertEquals(JobState.CANCELLED, finalState!!.state)
        assertEquals(null, finalState.lockedBy)
        assertEquals("test", finalState.cancelRequestedBy)
    }

    @Test
    fun `non-cooperative handler is force-killed after grace — ends FAILED`() = runBlocking {
        val koin = GlobalContext.get()
        val scheduler = koin.get<Scheduler>()
        val jobs = koin.get<JobRepository>()

        val jobId = scheduler.enqueue(StubbornJob())

        withTimeoutOrNull(5.seconds) { CancelPushHooks.stubbornStarted.await() }
            ?: error("StubbornHandler never started")

        val cancelResult = scheduler.cancel(jobId, by = "test")
        assertEquals(CancelResult.CANCEL_REQUESTED, cancelResult)

        // Handler ignores cancellation (blocking loop). After the 1s grace the worker must
        // force the row terminal FAILED rather than wait for the handler to finish.
        val finalState = pollForState(jobs, jobId, JobState.FAILED, timeoutMs = 6_000)
        assertNotNull(finalState, "Non-cooperative job should be force-FAILED after the grace window")
        assertEquals(JobState.FAILED, finalState!!.state)
        assertEquals(null, finalState.lockedBy)
    }

    private suspend fun pollForState(
        jobs: JobRepository,
        jobId: kotlin.uuid.Uuid,
        target: JobState,
        timeoutMs: Long,
    ) = withTimeoutOrNull(timeoutMs) {
        var snap = jobs.findById(jobId)
        while (snap?.state != target) {
            delay(25)
            snap = jobs.findById(jobId)
        }
        snap
    }
}
