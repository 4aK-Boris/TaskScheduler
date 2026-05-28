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
import kotlinx.serialization.SerializationException
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
import org.testcontainers.containers.RabbitMQContainer
import org.testcontainers.utility.DockerImageName
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.Uuid

@Serializable
private data class SchemaEvolvedJob(val payload: String) : Job

/**
 * Stands in for any payload that can't be deserialised against current code — a removed
 * required field, a renamed property, a changed type. We throw the same
 * [SerializationException] kotlinx would raise at decode time; the worker must treat it as
 * terminal regardless of retry policy.
 */
@JobType(SchemaEvolvedJob::class)
private class SchemaThrowingHandler(
    val invocations: AtomicInteger,
    val onFinalSignal: CompletableDeferred<Throwable>,
) : JobHandler<SchemaEvolvedJob> {

    override suspend fun execute(ctx: JobContext, job: SchemaEvolvedJob) {
        invocations.incrementAndGet()
        throw SerializationException("Field 'fromAddress' is required for type SchemaEvolvedJob but it was missing")
    }

    override suspend fun onFinalFailure(ctx: JobContext, job: SchemaEvolvedJob, error: Throwable) {
        if (!onFinalSignal.isCompleted) onFinalSignal.complete(error)
    }
}

/**
 * Payload schema evolution (DESIGN.md 22.9): a `SerializationException` is non-retriable —
 * the stored bytes won't start matching the data class on a re-run, so retrying just burns
 * the attempt budget. With a 3-attempt retry policy active (same as [RetryIntegrationTest],
 * which proves a *normal* exception retries 3×), a schema error must instead fail terminally
 * after a SINGLE invocation.
 *
 * Same `EXTERNAL_PG_URL` / `EXTERNAL_RABBIT_HOST` provisioning as the sibling tests.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SchemaEvolutionIntegrationTest {

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
    private lateinit var invocations: AtomicInteger
    private lateinit var onFinalSignal: CompletableDeferred<Throwable>
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

        invocations = AtomicInteger(0)
        onFinalSignal = CompletableDeferred()
        val testHandlerModule = module {
            single { SchemaThrowingHandler(invocations, onFinalSignal) } bind JobHandler::class
        }

        startKoin {
            modules(
                schedulerCoreModule {
                    nodeId = "test-schema"
                    // Retry policy that WOULD retry 3× for a normal exception — the point of
                    // the test is that a schema error short-circuits past it.
                    defaultMaxAttempts = 3
                    defaultRetryPolicy = FixedDelay(delay = 50.milliseconds, maxAttempts = 3)
                },
                schedulerPostgresModule {
                    this.dataSource = this@SchemaEvolutionIntegrationTest.dataSource
                    runMigrations = true
                },
                schedulerRabbitModule {
                    connectionFactory = this@SchemaEvolutionIntegrationTest.connectionFactory
                    queues = listOf("default")
                },
                schedulerInfraModule(),
                schedulerWorkerModule {
                    nodeId = "test-schema"
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
        runCatching { rabbit?.stop() }
        runCatching { postgres?.stop() }
    }

    @Test
    fun `SerializationException is terminal — failed after one invocation, never retried`() = runBlocking {
        val koin = GlobalContext.get()
        val scheduler = koin.get<Scheduler>()
        val workerPool = koin.get<WorkerPool>()
        val outboxPublisher = koin.get<OutboxPublisher>()
        val jobs = koin.get<JobRepository>()

        workerPool.start()
        outboxPublisher.start(publisherScope, intervalMillis = 20L)

        val jobId = scheduler.enqueue(SchemaEvolvedJob(payload = "v1-only"))

        val error = withTimeoutOrNull(15.seconds) { onFinalSignal.await() }
        assertNotNull(error, "onFinalFailure not called within 15s — invocations: ${invocations.get()}")
        assertTrue(error is SerializationException, "terminal cause should be the schema error, got ${error!!::class}")

        // The decisive assertion: a normal exception with this policy retries 3× (see
        // RetryIntegrationTest). A schema error must fail on the FIRST invocation.
        assertEquals(1, invocations.get(), "schema error must NOT be retried")

        val finalState = pollForState(jobs, jobId, JobState.FAILED, timeoutMs = 5_000)
        assertEquals(JobState.FAILED, finalState?.state)
        assertEquals(1, finalState!!.attempts, "no retry → attempts stays at 1")
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
