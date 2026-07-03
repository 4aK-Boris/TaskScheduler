@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package cs.trade.scheduler.engine.infra

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import cs.trade.scheduler.core.backend.SchedulerCoreConfig
import cs.trade.scheduler.core.backend.handler.Job
import cs.trade.scheduler.engine.infra.domain.usecases.PublishOutboxBatchUseCase
import cs.trade.scheduler.engine.infra.infrastructure.SchedulerInfraConfig
import cs.trade.scheduler.engine.infra.infrastructure.loops.OutboxPublisher
import cs.trade.scheduler.storage.postgres.infrastructure.PostgresStorageProvider
import cs.trade.scheduler.storage.postgres.infrastructure.repositories.IdempotencyLogRepositoryImpl
import cs.trade.scheduler.storage.postgres.infrastructure.repositories.JobDependencyRepositoryImpl
import cs.trade.scheduler.storage.postgres.infrastructure.repositories.JobEventRepositoryImpl
import cs.trade.scheduler.storage.postgres.infrastructure.repositories.JobRepositoryImpl
import cs.trade.scheduler.storage.postgres.infrastructure.repositories.JobRollupRepositoryImpl
import cs.trade.scheduler.storage.postgres.infrastructure.repositories.JobTypePauseRepositoryImpl
import cs.trade.scheduler.storage.postgres.infrastructure.repositories.OutboxRepositoryImpl
import cs.trade.scheduler.storage.postgres.infrastructure.repositories.RecurringJobRepositoryImpl
import cs.trade.scheduler.storage.postgres.infrastructure.repositories.WorkerRepositoryImpl
import cs.trade.scheduler.storage.postgres.infrastructure.scheduler.DefaultScheduler
import cs.trade.scheduler.storage.postgres.infrastructure.tables.OutboxTable
import cs.trade.scheduler.transport.rabbit.domain.ConsumerHandle
import cs.trade.scheduler.transport.rabbit.domain.JobTransport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import org.flywaydb.core.Flyway
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.suspendTransaction
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.testcontainers.containers.PostgreSQLContainer
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.Uuid

/**
 * Behavioural contract for [OutboxPublisher]'s `isLeader` gate (DESIGN.md 14.3).
 *
 *  - With `isLeader = { false }` the publisher tick is a no-op: outbox rows stay
 *    unpublished, no [JobTransport.publish] is ever called.
 *  - Flipping the gate to `true` drains the backlog within a couple of ticks.
 *
 * Wires a [FakeJobTransport] (no Rabbit needed) so we observe publish-or-not directly
 * via a counter, and check the outbox row state via the repo.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class OutboxPublisherLeaderGateTest {

    @Serializable
    data class Noop(val n: Long) : Job

    private companion object {
        private val externalUrl: String? = System.getenv("EXTERNAL_PG_URL")?.takeIf { it.isNotBlank() }
    }

    private lateinit var dataSource: HikariDataSource
    private lateinit var database: Database
    private lateinit var scheduler: DefaultScheduler
    private lateinit var outbox: OutboxRepositoryImpl
    private lateinit var jobs: JobRepositoryImpl
    private lateinit var jobTypePauses: JobTypePauseRepositoryImpl
    private var postgres: PostgreSQLContainer<*>? = null

    @BeforeAll
    fun setUp() {
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
        Flyway.configure().dataSource(dataSource).locations("classpath:scheduler/migration").load().migrate()
        database = Database.connect(dataSource)
        jobs = JobRepositoryImpl(database)
        outbox = OutboxRepositoryImpl(database)
        jobTypePauses = JobTypePauseRepositoryImpl(database)
        scheduler = DefaultScheduler(
            storage = PostgresStorageProvider(
                jobs = jobs,
                outbox = outbox,
                jobDependencies = JobDependencyRepositoryImpl(database),
                recurringJobs = RecurringJobRepositoryImpl(database),
                jobEvents = JobEventRepositoryImpl(database),
                workers = WorkerRepositoryImpl(database),
                idempotencyLog = IdempotencyLogRepositoryImpl(database),
                jobRollups = JobRollupRepositoryImpl(database),
                jobTypePauses = jobTypePauses,
            ),
            database = database,
            config = SchedulerCoreConfig().apply { nodeId = "test-leader-gate" },
        )
    }

    @AfterAll
    fun tearDown() {
        runCatching { dataSource.close() }
        runCatching { postgres?.stop() }
    }

    /**
     * Start each test from an empty outbox. The "still unpublished" / "drains the row"
     * assertions read `findUnpublished(limit = 1000)`; on a shared EXTERNAL_PG, hundreds of
     * leftover unpublished rows from other suites can push our row past the limit window (or
     * a leaked publisher could drain it), so we isolate by truncating first. `job CASCADE`
     * also clears the outbox. Mirrors `SafetyNetIntegrationTest.cleanTables`.
     */
    @BeforeEach
    fun cleanTables() {
        dataSource.connection.use { conn ->
            conn.createStatement().use { it.execute("TRUNCATE job RESTART IDENTITY CASCADE") }
        }
    }

    private fun newPublisher(transport: JobTransport) =
        OutboxPublisher(
            publishBatch = PublishOutboxBatchUseCase(
                outbox = outbox,
                transport = transport,
            ),
            outbox = outbox,
            config = SchedulerInfraConfig().apply {
                // Disable backlog WARN logging to keep test output clean.
                outboxBacklogWarnThreshold = null
            },
            coreConfig = SchedulerCoreConfig().apply { nodeId = "test-leader-gate" },
        )

    // Direct per-row check of OUR outbox row, by jobId — immune to other suites' leftover
    // rows and to the `findUnpublished(limit)` window (on a shared EXTERNAL_PG the limited
    // list can omit our row when many others exist). null = no row; true = exists &
    // unpublished; false = exists & published.
    private suspend fun ourRowUnpublished(jobId: Uuid): Boolean? = withContext(Dispatchers.IO) {
        suspendTransaction(db = database) {
            OutboxTable.selectAll().where { OutboxTable.jobId eq jobId }
                .firstOrNull()?.let { it[OutboxTable.publishedAt] == null }
        }
    }

    @Test
    fun `gated publisher — follower no-op, then flip to leader drains the row`() = runBlocking {
        // Combined single-test scenario so the two phases share one scope and one
        // FakeJobTransport — splitting into two tests created a flake where the prior
        // test's publisher coroutine outlived `scope.cancel()` long enough to drain the
        // next test's row before our delay started.
        val transport = FakeJobTransport()
        val isLeader = AtomicBoolean(false)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val pub = newPublisher(transport)
        try {
            val jobId = scheduler.enqueue(Noop(System.currentTimeMillis()))
            // Sanity: our row IS in the unpublished set right after enqueue.
            assertEquals(
                true, ourRowUnpublished(jobId),
                "Our enqueued job's outbox row must exist and be unpublished immediately after enqueue",
            )

            pub.start(scope, intervalMillis = 50L, isLeader = { isLeader.get() })

            // Phase 1 — follower. ~10 ticks; transport must stay untouched and our row
            // must remain unpublished. We assert per-row (not via countUnpublished) so
            // unrelated rows left behind by other tests don't pollute the signal.
            delay(600.milliseconds)
            assertEquals(0, transport.publishCount.get(), "transport.publish must not be invoked on follower")
            assertEquals(
                true, ourRowUnpublished(jobId),
                "Our row must still be unpublished while isLeader=false",
            )

            // Phase 2 — promote to leader. Next tick (≤ 50ms) publishes our row.
            isLeader.set(true)

            val ok = withTimeoutOrNull(3.seconds) {
                while (true) {
                    val stillThere = ourRowUnpublished(jobId) == true
                    if (!stillThere && transport.publishCount.get() >= 1) break
                    delay(25.milliseconds)
                }
                true
            }
            assertEquals(true, ok, "Backlog must drain within 3s of leader flip")
            assertEquals(jobId, transport.lastPublishedJobId, "transport.publish must have seen our jobId")
        } finally {
            scope.cancel()
            // Give the cancellation a beat to actually unwind so subsequent tests in this
            // session aren't racing against a half-cancelled publisher loop.
            delay(200.milliseconds)
        }
    }
}

/**
 * Records publishes synchronously; consume() throws to make sure the publisher path
 * never tries to consume (this is the publisher side, not the worker side).
 */
private class FakeJobTransport : JobTransport {
    val publishCount = AtomicInteger(0)
    @Volatile var lastPublishedJobId: Uuid? = null

    override suspend fun publish(jobId: Uuid, routingKey: String, priority: Int, delayMillis: Long) {
        lastPublishedJobId = jobId
        publishCount.incrementAndGet()
    }
    override suspend fun consume(
        queue: String,
        prefetch: Int,
        handler: suspend (jobId: Uuid) -> Unit,
    ): ConsumerHandle = error("not used in publisher-only test")
    override suspend fun cancelAllConsumers() {}
    override suspend fun close() {}
}
