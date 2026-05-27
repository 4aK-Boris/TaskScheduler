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
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
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
import org.testcontainers.containers.RabbitMQContainer
import org.testcontainers.utility.DockerImageName
import kotlin.time.Duration.Companion.seconds

/**
 * Full round-trip: `Scheduler.enqueue` → outbox → Rabbit publish → worker consume →
 * user [JobHandler] invoked → row marked SUCCEEDED.
 *
 * Each iteration of the loop owns one tick of [PublishOutboxBatchUseCase] (no background
 * loop spun up) so the test is deterministic.
 *
 * **PG provisioning.** Honours `EXTERNAL_PG_URL` for the shared scheduler-test-pg
 * setup; falls back to Testcontainers when absent. Rabbit always uses Testcontainers.
 * Manual lifecycle so the env override can short-circuit Docker for PG.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class WorkerIntegrationTest {

    @Serializable
    data class TestSendEmail(val userId: Long, val template: String) : Job

    /**
     * Captures the handler invocation so the test can await execution + assert the
     * deserialised payload survived the JSON round-trip intact.
     */
    @JobType(TestSendEmail::class)
    class TestSendEmailHandler(
        private val captured: CompletableDeferred<Pair<JobContext, TestSendEmail>>,
    ) : JobHandler<TestSendEmail> {
        override suspend fun execute(ctx: JobContext, job: TestSendEmail) {
            captured.complete(ctx to job)
        }
    }

    private companion object {
        private val rabbitImage: DockerImageName =
            DockerImageName.parse("heidiks/rabbitmq-delayed-message-exchange:3.13.0-management")
                .asCompatibleSubstituteFor("rabbitmq")

        private val externalUrl: String? = System.getenv("EXTERNAL_PG_URL")?.takeIf { it.isNotBlank() }
    }

    private lateinit var dataSource: HikariDataSource
    private lateinit var connectionFactory: ConnectionFactory
    private lateinit var handlerCalled: CompletableDeferred<Pair<JobContext, TestSendEmail>>
    private var postgres: PostgreSQLContainer<*>? = null
    private lateinit var rabbit: RabbitMQContainer

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
        rabbit = RabbitMQContainer(rabbitImage)
        rabbit.start()

        dataSource = HikariDataSource(HikariConfig().apply {
            this.jdbcUrl = jdbcUrl
            username = pgUser
            password = pgPass
            maximumPoolSize = 4
            addDataSourceProperty("stringtype", "unspecified")
        })

        connectionFactory = ConnectionFactory().apply {
            host = rabbit.host
            port = rabbit.amqpPort
            username = rabbit.adminUsername
            password = rabbit.adminPassword
        }

        handlerCalled = CompletableDeferred()
        val testHandlerModule = module {
            single { TestSendEmailHandler(handlerCalled) } bind JobHandler::class
        }

        startKoin {
            modules(
                schedulerCoreModule { nodeId = "test-worker" },
                schedulerPostgresModule {
                    this.dataSource = this@WorkerIntegrationTest.dataSource
                    runMigrations = true
                },
                schedulerRabbitModule {
                    connectionFactory = this@WorkerIntegrationTest.connectionFactory
                    queues = listOf("default")
                },
                schedulerInfraModule(),
                schedulerWorkerModule {
                    nodeId = "test-worker"
                    lockDuration = 60.seconds
                    queue("default", concurrency = 4)
                },
                testHandlerModule,
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
        runCatching { rabbit.stop() }
        runCatching { postgres?.stop() }
    }

    @Test
    fun `full enqueue to handler to SUCCEEDED round-trip`() = runBlocking {
        val koin = GlobalContext.get()
        val scheduler = koin.get<Scheduler>()
        val workerPool = koin.get<WorkerPool>()
        val publishBatch = koin.get<PublishOutboxBatchUseCase>()
        val jobs = koin.get<JobRepository>()

        workerPool.start()

        val jobId = scheduler.enqueue(TestSendEmail(userId = 7L, template = "welcome"))

        // Manual outbox tick — no background OutboxPublisher loop in this test.
        assertEquals(1, publishBatch().getOrThrow())

        // Handler must be called within 5s — accounts for Rabbit dispatch + amqp-client thread.
        val invocation = withTimeoutOrNull(5.seconds) { handlerCalled.await() }
        assertNotNull(invocation, "Handler was not invoked within 5s")
        val (ctx, payload) = invocation!!
        assertEquals(jobId, ctx.jobId)
        assertEquals(1, ctx.attempt, "First attempt should be numbered 1")
        assertEquals("default", ctx.queue)
        assertEquals(7L, payload.userId)
        assertEquals("welcome", payload.template)

        // After the handler returns, the worker calls markSucceeded — poll the row
        // because the markSucceeded write happens off the test coroutine.
        val finalState = pollForState(jobs, jobId, JobState.SUCCEEDED, timeoutMs = 5_000)
        assertEquals(JobState.SUCCEEDED, finalState?.state)
        assertNull(finalState!!.lockedBy, "lockedBy must be cleared on SUCCEEDED")
        assertNull(finalState.lockedUntil, "lockedUntil must be cleared on SUCCEEDED")
        assertNotNull(finalState.durationMs, "durationMs must be set on SUCCEEDED")
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
