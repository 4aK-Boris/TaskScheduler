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
import cs.trade.scheduler.core.backend.handler.retry.FixedDelay
import cs.trade.scheduler.core.backend.schedulerCoreModule
import cs.trade.scheduler.engine.infra.domain.usecases.PublishOutboxBatchUseCase
import cs.trade.scheduler.engine.infra.infrastructure.loops.OutboxPublisher
import cs.trade.scheduler.engine.infra.infrastructure.schedulerInfraModule
import cs.trade.scheduler.engine.worker.infrastructure.WorkerPool
import cs.trade.scheduler.engine.worker.infrastructure.schedulerWorkerModule
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
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.Uuid

/**
 * End-to-end retry path: handler that always throws → 3 attempts (per `maxAttempts = 3`)
 * → FAILED + `onFinalFailure` hook called with the last attempt's context.
 *
 * Backoff is a tiny [FixedDelay] so the whole test finishes in well under 5s.
 *
 * **PG provisioning.** Honours `EXTERNAL_PG_URL` for the shared scheduler-test-pg
 * setup; falls back to Testcontainers when absent. Rabbit always uses Testcontainers.
 * Manual lifecycle so the env override can short-circuit Docker for PG.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RetryIntegrationTest {

    @Serializable
    data class FlakyJob(val payload: String) : Job

    @JobType(FlakyJob::class)
    class AlwaysFailingHandler(
        val invocations: AtomicInteger,
        val onFinalSignal: CompletableDeferred<Triple<Int, JobContext, Throwable>>,
    ) : JobHandler<FlakyJob> {

        override suspend fun execute(ctx: JobContext, job: FlakyJob) {
            val n = invocations.incrementAndGet()
            throw RuntimeException("simulated failure (run #$n, ctx.attempt=${ctx.attempt})")
        }

        override suspend fun onFinalFailure(ctx: JobContext, job: FlakyJob, error: Throwable) {
            onFinalSignal.complete(Triple(ctx.attempt, ctx, error))
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
    private lateinit var publisherScope: CoroutineScope
    private lateinit var invocations: AtomicInteger
    private lateinit var onFinalSignal: CompletableDeferred<Triple<Int, JobContext, Throwable>>
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

        invocations = AtomicInteger(0)
        onFinalSignal = CompletableDeferred()
        val testHandlerModule = module {
            single { AlwaysFailingHandler(invocations, onFinalSignal) } bind JobHandler::class
        }

        startKoin {
            modules(
                schedulerCoreModule {
                    nodeId = "test-retry"
                    defaultMaxAttempts = 3
                    defaultRetryPolicy = FixedDelay(delay = 50.milliseconds, maxAttempts = 3)
                },
                schedulerPostgresModule {
                    this.dataSource = this@RetryIntegrationTest.dataSource
                    runMigrations = true
                },
                schedulerRabbitModule {
                    connectionFactory = this@RetryIntegrationTest.connectionFactory
                    queues = listOf("default")
                },
                schedulerInfraModule(),
                schedulerWorkerModule {
                    nodeId = "test-retry"
                    lockDuration = 60.seconds
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
        runCatching { rabbit.stop() }
        runCatching { postgres?.stop() }
    }

    @Test
    fun `handler fails 3 times then job goes FAILED with onFinalFailure called`() = runBlocking {
        val koin = GlobalContext.get()
        val scheduler = koin.get<Scheduler>()
        val workerPool = koin.get<WorkerPool>()
        val outboxPublisher = koin.get<OutboxPublisher>()
        val jobs = koin.get<JobRepository>()
        // Sanity: PublishOutboxBatchUseCase should be wired too.
        koin.get<PublishOutboxBatchUseCase>()

        workerPool.start()
        // 20ms tick so the retry outbox row gets published almost immediately after each failure.
        outboxPublisher.start(publisherScope, intervalMillis = 20L)

        val jobId = scheduler.enqueue(FlakyJob(payload = "x"))

        val final = withTimeoutOrNull(15.seconds) { onFinalSignal.await() }
        assertNotNull(final, "onFinalFailure was not called within 15s — invocations so far: ${invocations.get()}")
        val (finalAttempt, ctx, error) = final!!
        assertEquals(3, finalAttempt, "onFinalFailure must see ctx.attempt == 3 (the last attempt)")
        assertEquals(jobId, ctx.jobId)
        assertEquals(3, invocations.get(), "Handler should have been invoked exactly 3 times")
        assertEquals(true, error.message?.contains("simulated failure"))

        // The terminal write happens right after onFinalFailure returns — poll a bit.
        val finalState = pollForState(jobs, jobId, JobState.FAILED, timeoutMs = 5_000)
        assertEquals(JobState.FAILED, finalState?.state)
        assertEquals(3, finalState!!.attempts)
        assertEquals(null, finalState.lockedBy)
        assertEquals(null, finalState.lockedUntil)
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
