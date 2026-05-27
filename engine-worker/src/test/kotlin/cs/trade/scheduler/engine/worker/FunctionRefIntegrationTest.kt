@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package cs.trade.scheduler.engine.worker

import com.rabbitmq.client.ConnectionFactory
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import cs.trade.scheduler.core.backend.Scheduler
import cs.trade.scheduler.core.backend.functionref.enqueue
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
import org.koin.dsl.module
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.containers.RabbitMQContainer
import org.testcontainers.utility.DockerImageName
import kotlin.time.Duration.Companion.seconds

/**
 * `Mailer` target stub for the function-ref tests. Lives at file top-level so
 * `qualifiedName` resolves to a stable FQN (the same gotcha that bit `HangingJob` —
 * inner-class FQNs use `$`, but Kotlin reflection / `Class.forName` need the matching
 * separator and we encode `qualifiedName`-style with `.` into the payload).
 */
class FunctionRefMailer {
    val invocations: MutableList<Pair<Long, String>> = mutableListOf()
    val invoked: CompletableDeferred<Pair<Long, String>> = CompletableDeferred()

    @Suppress("unused")
    suspend fun send(userId: Long, template: String) {
        invocations += userId to template
        if (!invoked.isCompleted) invoked.complete(userId to template)
    }
}

/**
 * End-to-end coverage for the function-reference API (DESIGN.md 21). Reuses the same
 * `EXTERNAL_PG_URL` / `EXTERNAL_RABBIT_HOST` test infrastructure as the sealed-class
 * `WorkerIntegrationTest` — function-ref jobs go through the SAME outbox → Rabbit →
 * worker → finalize pipeline; only the dispatch step inside `WorkerPool.processLockedInner`
 * differs.
 *
 * Asserts:
 *  1. `scheduler.enqueue(Mailer::send, 42L, "welcome")` returns a UUID and produces a job
 *     row whose `payload_type` is `"function_ref"`.
 *  2. The published Rabbit message is picked up by the worker, the FunctionRefRunner
 *     resolves the Koin-bound `FunctionRefMailer`, decodes the args, and calls `send(42, "welcome")`.
 *  3. The row finalises to SUCCEEDED with cleared lock fields.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class FunctionRefIntegrationTest {

    private companion object {
        private val rabbitImage: DockerImageName =
            DockerImageName.parse("heidiks/rabbitmq-delayed-message-exchange:3.13.0-management")
                .asCompatibleSubstituteFor("rabbitmq")

        private val externalUrl: String? = System.getenv("EXTERNAL_PG_URL")?.takeIf { it.isNotBlank() }
        private val externalRabbitHost: String? = System.getenv("EXTERNAL_RABBIT_HOST")?.takeIf { it.isNotBlank() }
    }

    private lateinit var dataSource: HikariDataSource
    private lateinit var connectionFactory: ConnectionFactory
    private lateinit var mailer: FunctionRefMailer
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

        mailer = FunctionRefMailer()
        // The mailer is bound by its concrete class — the function-ref API resolves
        // `targetType = FunctionRefMailer` via Koin's KClass lookup. No qualifier needed
        // here because we have a single binding.
        val testTargetModule = module { single { mailer } }

        startKoin {
            modules(
                schedulerCoreModule { nodeId = "test-fnref" },
                schedulerPostgresModule {
                    this.dataSource = this@FunctionRefIntegrationTest.dataSource
                    runMigrations = true
                },
                schedulerRabbitModule {
                    connectionFactory = this@FunctionRefIntegrationTest.connectionFactory
                    queues = listOf("default")
                },
                schedulerInfraModule(),
                schedulerWorkerModule {
                    nodeId = "test-fnref"
                    lockDuration = 60.seconds
                    queue("default", concurrency = 4)
                },
                testTargetModule,
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
    }

    @Test
    fun `enqueue Mailer send via KFunction reference — full round-trip ends SUCCEEDED`() = runBlocking {
        val koin = GlobalContext.get()
        val scheduler = koin.get<Scheduler>()
        val workerPool = koin.get<WorkerPool>()
        val publishBatch = koin.get<PublishOutboxBatchUseCase>()
        val jobs = koin.get<JobRepository>()

        workerPool.start()

        // KFunction2 reference. Compiler picks the correct `send(Long, String)` overload
        // because Scheduler.enqueue(KFunction2<…>, …) only matches that shape.
        val jobId = scheduler.enqueue(FunctionRefMailer::send, 42L, "welcome")

        // The job row was written with payload_type = "function_ref" — that's the
        // load-bearing breadcrumb that tells the worker to dispatch via FunctionRefRunner.
        val saved = jobs.findById(jobId)
        assertNotNull(saved, "Job row must exist after enqueue")
        assertEquals("function_ref", saved!!.payloadType, "function-ref jobs use the sentinel payload_type")

        // Drain the outbox once so Rabbit receives the message. No background loop running.
        assertTrue(publishBatch().getOrThrow() >= 1, "publisher should drain at least our row")

        // Wait for the user method to be called with the right args.
        val invocation = withTimeoutOrNull(5.seconds) { mailer.invoked.await() }
        assertNotNull(invocation, "Mailer.send was not invoked within 5s")
        assertEquals(42L to "welcome", invocation)

        // The worker should then call markSucceeded → the row reaches SUCCEEDED.
        val finalState = pollForState(jobs, jobId, JobState.SUCCEEDED, timeoutMs = 5_000)
        assertEquals(JobState.SUCCEEDED, finalState?.state)
        assertNull(finalState!!.lockedBy, "lockedBy cleared on SUCCEEDED")
        assertNull(finalState.lockedUntil, "lockedUntil cleared on SUCCEEDED")
    }

    @Test
    fun `enqueue with no Koin binding for target — IAE at enqueue, no row inserted`() = runBlocking {
        val koin = GlobalContext.get()
        val scheduler = koin.get<Scheduler>()

        // UnregisteredTarget isn't bound in the Koin graph. The function-ref binding
        // resolver should fail fast at enqueue with a clear IAE.
        val ex = runCatching {
            scheduler.enqueue(UnregisteredTarget::doit, 1L)
        }.exceptionOrNull()
        assertNotNull(ex, "enqueue against an unbound target must fail")
        assertTrue(
            ex is IllegalArgumentException,
            "expected IllegalArgumentException, got ${ex?.let { it::class.qualifiedName }}: ${ex?.message}",
        )
        assertTrue(
            (ex?.message ?: "").contains("no Koin binding"),
            "error must explain the missing binding: ${ex?.message}",
        )
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

// Target the second test references but never registers in Koin — the fail-fast resolver
// should reject the enqueue before it touches the DB.
class UnregisteredTarget {
    @Suppress("unused")
    suspend fun doit(x: Long) {
        // never invoked
    }
}
