@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package cs.trade.scheduler.engine.worker

import com.rabbitmq.client.ConnectionFactory
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import cs.trade.scheduler.core.backend.Scheduler
import cs.trade.scheduler.core.backend.handler.Job
import cs.trade.scheduler.core.backend.handler.JobCancellationException
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
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

@Serializable
private data class LongRunningJob(val maxIterations: Int = 200) : Job

@JobType(LongRunningJob::class)
private class CooperativeHandler(
    val handlerStarted: CompletableDeferred<Unit>,
    val cancelledNormally: CompletableDeferred<Unit>,
) : JobHandler<LongRunningJob> {

    override suspend fun execute(ctx: JobContext, job: LongRunningJob) {
        handlerStarted.complete(Unit)
        repeat(job.maxIterations) {
            if (ctx.isCancellationRequested()) {
                cancelledNormally.complete(Unit)
                throw JobCancellationException("user asked to stop on iteration $it")
            }
            delay(40)
        }
        error("Handler reached iteration limit without seeing cancel — bug or slow test")
    }
}

/**
 * Full pipeline cancel-in-flight: while a long-running handler is polling
 * `isCancellationRequested`, an external `Scheduler.cancel` call should be observed by
 * the handler within one poll cycle. Handler throws [JobCancellationException] →
 * worker marks the row CANCELLED (terminal, no retry).
 *
 * **PG provisioning.** Honours `EXTERNAL_PG_URL` for the shared scheduler-test-pg
 * setup; falls back to Testcontainers when absent.
 *
 * **Rabbit provisioning.** Honours `EXTERNAL_RABBIT_HOST` (+ optional `_PORT`, `_USER`,
 * `_PASSWORD`) so CI can point at a shared `scheduler-test-rabbit` with the
 * delayed-message-exchange plugin baked in. Falls back to a Testcontainers Rabbit when
 * absent. Manual lifecycle so the env override can short-circuit Docker.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CancelInFlightIntegrationTest {

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
    private lateinit var handlerStarted: CompletableDeferred<Unit>
    private lateinit var cancelledNormally: CompletableDeferred<Unit>
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

        handlerStarted = CompletableDeferred()
        cancelledNormally = CompletableDeferred()
        val testHandlerModule = module {
            single { CooperativeHandler(handlerStarted, cancelledNormally) } bind JobHandler::class
        }

        startKoin {
            modules(
                schedulerCoreModule { nodeId = "test-cancel-inflight" },
                schedulerPostgresModule {
                    this.dataSource = this@CancelInFlightIntegrationTest.dataSource
                    runMigrations = true
                },
                schedulerRabbitModule {
                    connectionFactory = this@CancelInFlightIntegrationTest.connectionFactory
                    queues = listOf("default")
                },
                schedulerInfraModule(),
                schedulerWorkerModule {
                    nodeId = "test-cancel-inflight"
                    lockDuration = 60.seconds
                    heartbeatInterval = 200.milliseconds
                    queue("default", concurrency = 4)
                },
                testHandlerModule,
            )
        }

        publisherScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
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
    fun `cancel during PROCESSING ends as terminal CANCELLED`() = runBlocking {
        val koin = GlobalContext.get()
        val scheduler = koin.get<Scheduler>()
        val workerPool = koin.get<WorkerPool>()
        val outboxPublisher = koin.get<OutboxPublisher>()
        val jobs = koin.get<JobRepository>()

        workerPool.start()
        outboxPublisher.start(publisherScope, intervalMillis = 20L)

        val jobId = scheduler.enqueue(LongRunningJob())

        // Wait for handler to actually start running.
        withTimeoutOrNull(5.seconds) { handlerStarted.await() }
            ?: error("Handler never started")

        // Now ask to cancel — handler should observe within a poll cycle (40ms).
        val cancelResult = scheduler.cancel(jobId, by = "test")
        assertEquals(CancelResult.CANCEL_REQUESTED, cancelResult)

        // Handler hits the polling check, throws JobCancellationException.
        withTimeoutOrNull(5.seconds) { cancelledNormally.await() }
            ?: error("Handler never observed the cancellation")

        // Worker writes terminal CANCELLED after handler throws.
        val finalState = pollForState(jobs, jobId, JobState.CANCELLED, timeoutMs = 5_000)
        assertNotNull(finalState, "Job should reach CANCELLED state after handler throws")
        assertEquals(JobState.CANCELLED, finalState!!.state)
        assertEquals(null, finalState.lockedBy)
        assertEquals(null, finalState.lockedUntil)
        assertEquals("test", finalState.cancelRequestedBy)
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
