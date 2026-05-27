@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package cs.trade.scheduler.storage.postgres

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import cs.trade.scheduler.core.backend.EnqueueOptions
import cs.trade.scheduler.core.backend.SchedulerCoreConfig
import cs.trade.scheduler.core.backend.handler.Job
import cs.trade.scheduler.shared.CancelResult
import cs.trade.scheduler.shared.JobState
import cs.trade.scheduler.storage.postgres.infrastructure.PostgresStorageProvider
import cs.trade.scheduler.storage.postgres.infrastructure.repositories.JobDependencyRepositoryImpl
import cs.trade.scheduler.storage.postgres.infrastructure.repositories.JobEventRepositoryImpl
import cs.trade.scheduler.storage.postgres.infrastructure.repositories.JobRepositoryImpl
import cs.trade.scheduler.storage.postgres.infrastructure.repositories.OutboxRepositoryImpl
import cs.trade.scheduler.storage.postgres.infrastructure.repositories.RecurringJobRepositoryImpl
import cs.trade.scheduler.storage.postgres.infrastructure.scheduler.DefaultScheduler
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import org.flywaydb.core.Flyway
import org.jetbrains.exposed.v1.jdbc.Database
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.testcontainers.containers.PostgreSQLContainer
import kotlin.uuid.Uuid

/**
 * Covers the non-PROCESSING cancel paths — terminal CANCELLED, ALREADY_TERMINAL,
 * NOT_FOUND. Cancel-during-execution lives in `:engine-worker` since it needs the
 * Rabbit + worker pipeline.
 *
 * **PG provisioning.** Honours `EXTERNAL_PG_URL` for the shared scheduler-test-pg
 * setup; falls back to Testcontainers when absent. Manual lifecycle so the env
 * override can short-circuit Docker.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CancelIntegrationTest {

    @Serializable
    data class Noop(val n: Long) : Job

    private companion object {
        private val externalUrl: String? = System.getenv("EXTERNAL_PG_URL")?.takeIf { it.isNotBlank() }
    }

    private lateinit var dataSource: HikariDataSource
    private lateinit var database: Database
    private lateinit var jobs: JobRepositoryImpl
    private lateinit var outbox: OutboxRepositoryImpl
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
        Flyway.configure().dataSource(dataSource).load().migrate()
        database = Database.connect(dataSource)
        jobs = JobRepositoryImpl(database)
        outbox = OutboxRepositoryImpl(database)
        val deps = JobDependencyRepositoryImpl(database)
        val recurring = RecurringJobRepositoryImpl(database)
        val jobEventsRepo = JobEventRepositoryImpl(database)
        val workersRepo = cs.trade.scheduler.storage.postgres.infrastructure.repositories.WorkerRepositoryImpl(database)
        scheduler = DefaultScheduler(
            storage = PostgresStorageProvider(
                jobs = jobs,
                outbox = outbox,
                jobDependencies = deps,
                recurringJobs = recurring,
                jobEvents = jobEventsRepo,
                workers = workersRepo,
                idempotencyLog = cs.trade.scheduler.storage.postgres.infrastructure.repositories.IdempotencyLogRepositoryImpl(database),
                jobRollups = cs.trade.scheduler.storage.postgres.infrastructure.repositories.JobRollupRepositoryImpl(database),
                jobTypePauses = cs.trade.scheduler.storage.postgres.infrastructure.repositories.JobTypePauseRepositoryImpl(database),
            ),
            database = database,
            config = SchedulerCoreConfig().apply { nodeId = "test-cancel" },
        )
    }

    @AfterAll
    fun tearDown() {
        runCatching { dataSource.close() }
        runCatching { postgres?.stop() }
    }

    @Test
    fun `cancel ENQUEUED flips row to CANCELLED and returns CANCELLED`() = runBlocking {
        val jobId = scheduler.enqueue(Noop(1))
        assertEquals(JobState.ENQUEUED, jobs.findById(jobId)!!.state)

        val result = scheduler.cancel(jobId, by = "alice")
        assertEquals(CancelResult.CANCELLED, result)

        val after = jobs.findById(jobId)
        assertNotNull(after)
        assertEquals(JobState.CANCELLED, after!!.state)
        assertEquals(null, after.lockedBy)
        assertEquals(null, after.lockedUntil)
    }

    @Test
    fun `cancel terminal row returns ALREADY_TERMINAL and does not change state`() = runBlocking {
        val jobId = scheduler.enqueue(Noop(2))
        // Move to SUCCEEDED via the generic transitionState (no version side effects).
        val moved = jobs.transitionState(
            id = jobId,
            expectedVersion = 0,
            newState = JobState.SUCCEEDED,
            lockedBy = null,
            lockedUntilMillis = null,
        )
        assertEquals(true, moved)

        val result = scheduler.cancel(jobId)
        assertEquals(CancelResult.ALREADY_TERMINAL, result)
        assertEquals(JobState.SUCCEEDED, jobs.findById(jobId)!!.state)
    }

    @Test
    fun `cancel already-cancelled row returns CANCELLED not ALREADY_TERMINAL`() = runBlocking {
        val jobId = scheduler.enqueue(Noop(3))
        assertEquals(CancelResult.CANCELLED, scheduler.cancel(jobId))
        // Second call: still CANCELLED (the state IS already terminal, but we surface a
        // friendlier outcome since the user's intent — cancel — is satisfied).
        assertEquals(CancelResult.CANCELLED, scheduler.cancel(jobId))
    }

    @Test
    fun `cancel unknown id returns NOT_FOUND`() = runBlocking {
        assertEquals(CancelResult.NOT_FOUND, scheduler.cancel(Uuid.random()))
    }
}
