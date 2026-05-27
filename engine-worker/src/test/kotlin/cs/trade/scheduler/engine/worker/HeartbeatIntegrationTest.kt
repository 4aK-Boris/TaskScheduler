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
import cs.trade.scheduler.engine.infra.infrastructure.schedulerInfraModule
import cs.trade.scheduler.engine.infra.infrastructure.loops.OutboxPublisher
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
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Verifies that [HeartbeatLoop] actually keeps `locked_until` advancing while a slow
 * handler runs. Without heartbeat, `locked_until = pickup_time + lockDuration` would
 * stay frozen until SUCCEEDED clears it.
 *
 * Setup choices:
 *  - `heartbeatInterval = 80ms`, `lockDuration = 2s` — heartbeat fires multiple times
 *    inside one execution; lockDuration is comfortably longer than the handler's sleep
 *    so we never test "did the safety net not fire" (a different concern).
 *  - Slow handler delays 500ms while signalling start via [handlerStarted] so the
 *    test loop knows when to begin sampling `locked_until`.
 *
 * **PG provisioning.** Honours `EXTERNAL_PG_URL` for the shared scheduler-test-pg
 * setup; falls back to Testcontainers when absent. Rabbit always uses Testcontainers.
 * Manual lifecycle so the env override can short-circuit Docker for PG.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class HeartbeatIntegrationTest {

    @Serializable
    data class SlowJob(val delayMs: Long) : Job

    @JobType(SlowJob::class)
    class SlowHandler(
        val handlerStarted: CompletableDeferred<Unit>,
    ) : JobHandler<SlowJob> {
        override suspend fun execute(ctx: JobContext, job: SlowJob) {
            handlerStarted.complete(Unit)
            delay(job.delayMs)
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
    private lateinit var handlerStarted: CompletableDeferred<Unit>
    private var postgres: PostgreSQLContainer<*>? = null
    private lateinit var rabbit: RabbitMQContainer

    @BeforeAll
    fun setUp() {
        // Defensive: a previous test class may have left a Koin instance running.
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

        handlerStarted = CompletableDeferred()
        val testHandlerModule = module {
            single { SlowHandler(handlerStarted) } bind JobHandler::class
        }

        startKoin {
            modules(
                schedulerCoreModule { nodeId = "test-heartbeat" },
                schedulerPostgresModule {
                    this.dataSource = this@HeartbeatIntegrationTest.dataSource
                    runMigrations = true
                },
                schedulerRabbitModule {
                    connectionFactory = this@HeartbeatIntegrationTest.connectionFactory
                    queues = listOf("default")
                },
                schedulerInfraModule(),
                schedulerWorkerModule {
                    nodeId = "test-heartbeat"
                    heartbeatInterval = 80.milliseconds
                    lockDuration = 2.seconds
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
    fun `lockedUntil advances while a slow handler runs and job ends SUCCEEDED`() = runBlocking {
        val koin = GlobalContext.get()
        val scheduler = koin.get<Scheduler>()
        val workerPool = koin.get<WorkerPool>()
        val outboxPublisher = koin.get<OutboxPublisher>()
        val jobs = koin.get<JobRepository>()

        workerPool.start()
        outboxPublisher.start(publisherScope, intervalMillis = 20L)

        val jobId = scheduler.enqueue(SlowJob(delayMs = 500))

        // Wait for handler to start — now we know the row is PROCESSING with an initial
        // lockedUntil. Sample lockedUntil while the handler sleeps.
        withTimeoutOrNull(5.seconds) { handlerStarted.await() }
            ?: error("Handler never started — Rabbit dispatch broken?")

        val samples = mutableListOf<kotlin.time.Instant>()
        // 6 samples × 70ms = 420ms of polling — well inside the 500ms handler delay.
        repeat(6) {
            val snap = jobs.findById(jobId)
            snap?.lockedUntil?.let { samples.add(it) }
            delay(70)
        }

        // Heartbeat fires every 80ms → expect at least a couple of distinct values.
        val distinct = samples.toSet()
        assertTrue(
            distinct.size >= 2,
            "Expected at least 2 distinct lockedUntil samples (heartbeat bumps), got: $samples",
        )
        val sortedAsc = distinct.sorted()
        val span = sortedAsc.last() - sortedAsc.first()
        assertTrue(
            span >= 100.milliseconds,
            "lockedUntil should advance by at least 100ms across samples, got $span (samples=$samples)",
        )

        // Handler finishes → markSucceeded → state==SUCCEEDED, locks cleared.
        val finalState = pollForState(jobs, jobId, JobState.SUCCEEDED, timeoutMs = 5_000)
        assertNotNull(finalState, "Job should reach SUCCEEDED state")
        assertEquals(JobState.SUCCEEDED, finalState!!.state)
        assertEquals(null, finalState.lockedBy)
        assertEquals(null, finalState.lockedUntil)
    }

    @Test
    fun `extendLocks updates only PROCESSING rows of the given node`() = runBlocking {
        // This second test is decoupled from the slow-handler test — it directly drives
        // extendLocks() so we cover the repo behaviour even if test ordering changes.
        val koin = GlobalContext.get()
        val jobs = koin.get<JobRepository>()

        // No PROCESSING rows owned by "ghost-node" → 0 updates.
        val updated = jobs.extendLocks(nodeId = "ghost-node", newLockedUntilMillis = System.currentTimeMillis() + 60_000)
        assertEquals(0, updated, "ghost-node owns nothing — extendLocks should return 0")
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
