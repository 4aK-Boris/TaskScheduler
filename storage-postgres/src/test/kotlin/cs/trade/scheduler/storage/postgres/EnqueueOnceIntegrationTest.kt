@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package cs.trade.scheduler.storage.postgres

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import cs.trade.scheduler.core.backend.EnqueueOptions
import cs.trade.scheduler.core.backend.SchedulerCoreConfig
import cs.trade.scheduler.core.backend.handler.Job
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
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.testcontainers.containers.PostgreSQLContainer

/**
 * Validates the idempotency_key path:
 *  1. Two `enqueueOnce` calls with the same key while the first is still active →
 *     same UUID returned, only one row, only one outbox entry.
 *  2. Once the first row hits a terminal state (the partial unique index excludes it),
 *     a third `enqueueOnce` with the same key creates a fresh row + outbox.
 *
 * **PG provisioning.** Honours `EXTERNAL_PG_URL` for the shared scheduler-test-pg
 * setup; falls back to Testcontainers when absent. Manual lifecycle (no
 * `@Testcontainers` / `@Container`) so the env override can short-circuit Docker.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class EnqueueOnceIntegrationTest {

    @Serializable
    data class WelcomeEmail(val userId: Long) : Job

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
            config = SchedulerCoreConfig().apply { nodeId = "test-once" },
        )
    }

    @AfterAll
    fun tearDown() {
        runCatching { dataSource.close() }
        runCatching { postgres?.stop() }
    }

    @Test
    fun `two enqueueOnce with same key while first is active return the same id`() = runBlocking {
        val key = "welcome-user-42-${System.nanoTime()}"

        val id1 = scheduler.enqueueOnce(key, WelcomeEmail(42), EnqueueOptions())
        val id2 = scheduler.enqueueOnce(key, WelcomeEmail(42), EnqueueOptions())

        assertEquals(id1, id2, "Second enqueueOnce must return the same UUID as the first")

        val row = jobs.findById(id1)
        assertNotNull(row)
        assertEquals(JobState.ENQUEUED, row!!.state)
        assertEquals(key, row.idempotencyKey)

        val unpublishedForKey = outbox.findUnpublished(limit = 100).filter { it.jobId == id1 }
        assertEquals(1, unpublishedForKey.size, "Only one outbox row expected for the same key")
    }

    @Test
    fun `enqueueOnce after terminal state creates a fresh row`() = runBlocking {
        val key = "reset-user-99-${System.nanoTime()}"

        val id1 = scheduler.enqueueOnce(key, WelcomeEmail(99), EnqueueOptions())
        val original = jobs.findById(id1)!!

        // Move the first job to a terminal state. transitionState with newState=SUCCEEDED
        // clears the lock; partial-unique-index predicate no longer covers it.
        val transitioned = jobs.transitionState(
            id = id1,
            expectedVersion = original.version,
            newState = JobState.SUCCEEDED,
            lockedBy = null,
            lockedUntilMillis = null,
        )
        assertEquals(true, transitioned)

        val id2 = scheduler.enqueueOnce(key, WelcomeEmail(99), EnqueueOptions())
        assertNotEquals(id1, id2, "After terminal, the key is free — a fresh row must be created")

        val freshRow = jobs.findById(id2)!!
        assertEquals(JobState.ENQUEUED, freshRow.state)
        assertEquals(key, freshRow.idempotencyKey)
    }
}
