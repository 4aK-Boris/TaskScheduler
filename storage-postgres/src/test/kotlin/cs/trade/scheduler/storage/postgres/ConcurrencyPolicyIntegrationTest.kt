@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package cs.trade.scheduler.storage.postgres

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import cs.trade.scheduler.core.backend.EnqueueOptions
import cs.trade.scheduler.core.backend.SchedulerCoreConfig
import cs.trade.scheduler.core.backend.handler.Job
import cs.trade.scheduler.shared.ConcurrencyPolicy
import cs.trade.scheduler.shared.JobState
import cs.trade.scheduler.shared.OnFailure
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
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import org.flywaydb.core.Flyway
import org.jetbrains.exposed.v1.jdbc.Database
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.testcontainers.containers.PostgreSQLContainer
import kotlin.time.Duration.Companion.minutes

/**
 * Validates the [ConcurrencyPolicy] handling added to `enqueueOnce` (DESIGN.md 17.4) at the
 * storage layer — the row-level effects (leader/successor slots, cancellation, dep edges).
 * The "successor actually runs after the leader finishes" promotion is the existing,
 * separately-tested FinalizeJobUseCase IGNORE path; here we verify the rows it acts on are set
 * up correctly, plus the admin-cancel promotion that DefaultScheduler.cancel owns.
 *
 * Honours `EXTERNAL_PG_URL` (shared scheduler-test-pg), Testcontainers fallback otherwise.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ConcurrencyPolicyIntegrationTest {

    @Serializable
    data class Rebuild(val tenant: Long) : Job

    private companion object {
        private val externalUrl: String? = System.getenv("EXTERNAL_PG_URL")?.takeIf { it.isNotBlank() }
        private const val NODE = "test-concurrency"
    }

    private lateinit var dataSource: HikariDataSource
    private lateinit var database: Database
    private lateinit var jobs: JobRepositoryImpl
    private lateinit var outbox: OutboxRepositoryImpl
    private lateinit var deps: JobDependencyRepositoryImpl
    private lateinit var scheduler: DefaultScheduler
    private var postgres: PostgreSQLContainer<*>? = null

    @BeforeAll
    fun setUp() {
        val jdbcUrl: String; val pgUser: String; val pgPass: String
        if (externalUrl != null) {
            jdbcUrl = externalUrl
            pgUser = System.getenv("EXTERNAL_PG_USER") ?: "scheduler"
            pgPass = System.getenv("EXTERNAL_PG_PASSWORD") ?: "scheduler"
        } else {
            val tc = PostgreSQLContainer("postgres:16-alpine")
                .withDatabaseName("scheduler").withUsername("scheduler").withPassword("scheduler")
            tc.start()
            postgres = tc
            jdbcUrl = tc.jdbcUrl; pgUser = tc.username; pgPass = tc.password
        }
        dataSource = HikariDataSource(HikariConfig().apply {
            this.jdbcUrl = jdbcUrl; username = pgUser; password = pgPass
            maximumPoolSize = 4
            addDataSourceProperty("stringtype", "unspecified")
        })
        Flyway.configure().dataSource(dataSource).locations("classpath:scheduler/migration").load().migrate()
        database = Database.connect(dataSource)
        jobs = JobRepositoryImpl(database)
        outbox = OutboxRepositoryImpl(database)
        deps = JobDependencyRepositoryImpl(database)
        scheduler = DefaultScheduler(
            storage = PostgresStorageProvider(
                jobs = jobs,
                outbox = outbox,
                jobDependencies = deps,
                recurringJobs = RecurringJobRepositoryImpl(database),
                jobEvents = JobEventRepositoryImpl(database),
                workers = WorkerRepositoryImpl(database),
                idempotencyLog = IdempotencyLogRepositoryImpl(database),
                jobRollups = JobRollupRepositoryImpl(database),
                jobTypePauses = JobTypePauseRepositoryImpl(database),
            ),
            database = database,
            config = SchedulerCoreConfig().apply { nodeId = NODE },
        )
    }

    @AfterAll
    fun tearDown() {
        runCatching { dataSource.close() }
        runCatching { postgres?.stop() }
    }

    private fun key(name: String) = "$name-${System.nanoTime()}"

    @Test
    fun `REPLACE on a queued leader cancels it and the new job becomes leader`() = runBlocking {
        val k = key("replace-queued")
        val id1 = scheduler.enqueueOnce(k, Rebuild(1))
        val id2 = scheduler.enqueueOnce(k, Rebuild(1), policy = ConcurrencyPolicy.REPLACE)

        assertNotEquals(id1, id2)
        assertEquals(JobState.CANCELLED, jobs.findById(id1)!!.state, "queued leader cancelled immediately")
        val leader = jobs.findById(id2)!!
        assertEquals(JobState.ENQUEUED, leader.state)
        assertEquals(k, leader.idempotencyKey)
        assertEquals(id2, jobs.findLeaderByIdempotencyKey(k)?.id)
    }

    @Test
    fun `REPLACE on a running leader requests cancel and parks a successor`() = runBlocking {
        val k = key("replace-running")
        val id1 = scheduler.enqueueOnce(k, Rebuild(2))
        assertNotNull(jobs.pickup(id1, NODE, 60_000L), "leader picked up → PROCESSING")

        val id2 = scheduler.enqueueOnce(k, Rebuild(2), policy = ConcurrencyPolicy.REPLACE)
        assertNotEquals(id1, id2)

        val running = jobs.findById(id1)!!
        assertEquals(JobState.PROCESSING, running.state, "running leader stays PROCESSING (cooperative cancel)")
        assertNotNull(running.cancelRequestedAt, "cancel was requested")

        val successor = jobs.findById(id2)!!
        assertEquals(JobState.AWAITING_DEPS, successor.state)
        assertEquals(1, successor.pendingDeps)
        assertEquals(k, successor.idempotencyKey)
        assertEquals(id2, jobs.findSuccessorByIdempotencyKey(k)?.id)
        val parents = deps.findParentsOfChild(id2)
        assertEquals(1, parents.size)
        assertEquals(id1, parents.single().parentId)
        assertEquals(OnFailure.IGNORE, parents.single().onFailure)
    }

    @Test
    fun `ENQUEUE_AFTER parks one successor and dedups repeats`() = runBlocking {
        val k = key("after")
        val id1 = scheduler.enqueueOnce(k, Rebuild(3))
        val id2 = scheduler.enqueueOnce(k, Rebuild(3), policy = ConcurrencyPolicy.ENQUEUE_AFTER)
        val id3 = scheduler.enqueueOnce(k, Rebuild(3), policy = ConcurrencyPolicy.ENQUEUE_AFTER)

        assertNotEquals(id1, id2)
        assertEquals(id2, id3, "second ENQUEUE_AFTER returns the existing parked successor")
        assertEquals(JobState.AWAITING_DEPS, jobs.findById(id2)!!.state)
    }

    @Test
    fun `REPLACE supersedes an existing parked successor`() = runBlocking {
        val k = key("replace-successor")
        val id1 = scheduler.enqueueOnce(k, Rebuild(4))
        assertNotNull(jobs.pickup(id1, NODE, 60_000L))
        val id2 = scheduler.enqueueOnce(k, Rebuild(4), policy = ConcurrencyPolicy.ENQUEUE_AFTER)
        val id3 = scheduler.enqueueOnce(k, Rebuild(4), policy = ConcurrencyPolicy.REPLACE)

        assertEquals(JobState.CANCELLED, jobs.findById(id2)!!.state, "older successor superseded")
        assertEquals(JobState.AWAITING_DEPS, jobs.findById(id3)!!.state)
        assertEquals(id3, jobs.findSuccessorByIdempotencyKey(k)?.id)
    }

    @Test
    fun `admin cancel of a non-running leader promotes its IGNORE successor`() = runBlocking {
        val k = key("cancel-promotes")
        val id1 = scheduler.enqueueOnce(k, Rebuild(5))
        val locked = jobs.pickup(id1, NODE, 60_000L)!!            // → PROCESSING
        val id2 = scheduler.enqueueOnce(k, Rebuild(5), policy = ConcurrencyPolicy.ENQUEUE_AFTER) // parks successor
        // Move the leader to a non-PROCESSING state so cancel() takes the markCancelled path
        // (not requestCancellation) — that's the path that must promote the IGNORE successor.
        assertTrue(jobs.markForRetry(id1, locked.version, backoff = 1.minutes))

        scheduler.cancel(id1, by = "admin")

        assertEquals(JobState.CANCELLED, jobs.findById(id1)!!.state)
        assertEquals(JobState.ENQUEUED, jobs.findById(id2)!!.state, "successor promoted, not stranded")
        assertEquals(0, jobs.findById(id2)!!.pendingDeps)
        val outboxForSuccessor = outbox.findUnpublished(limit = 200).filter { it.jobId == id2 }
        assertEquals(1, outboxForSuccessor.size, "promoted successor got an outbox row")
        // Key is free again (no active leader/successor).
        assertNull(jobs.findSuccessorByIdempotencyKey(k))
    }
}
