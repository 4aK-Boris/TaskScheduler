@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package cs.trade.scheduler.engine.infra

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import cs.trade.scheduler.core.backend.EnqueueOptions
import cs.trade.scheduler.core.backend.SchedulerCoreConfig
import cs.trade.scheduler.core.backend.handler.Job
import cs.trade.scheduler.engine.infra.domain.usecases.FastForwardScheduledJobsUseCase
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
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.testcontainers.containers.PostgreSQLContainer
import kotlin.time.Clock
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * Covers the two branches of `scheduleAt` + the [FastForwardScheduledJobsUseCase]
 * promotion path, without spinning up Rabbit or a worker — we observe the job +
 * outbox rows directly via repositories. Each test uses a fresh UUID so tests don't
 * interfere even though they share the same DB across the class lifetime.
 *
 * **PG provisioning.** Honours `EXTERNAL_PG_URL` for the shared scheduler-test-pg
 * setup; falls back to Testcontainers when absent. Manual lifecycle so the env
 * override can short-circuit Docker.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class FastForwardIntegrationTest {

    @Serializable
    data class TestJob(val n: Long) : Job

    private companion object {
        private val externalUrl: String? = System.getenv("EXTERNAL_PG_URL")?.takeIf { it.isNotBlank() }
    }

    private lateinit var dataSource: HikariDataSource
    private lateinit var database: Database
    private lateinit var jobs: JobRepositoryImpl
    private lateinit var outbox: OutboxRepositoryImpl
    private lateinit var coreConfig: SchedulerCoreConfig
    private lateinit var scheduler: DefaultScheduler
    private lateinit var fastForward: FastForwardScheduledJobsUseCase
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
        coreConfig = SchedulerCoreConfig().apply {
            nodeId = "test-fastforward"
            // Tighter than the 24h default so we can test both branches with realistic deltas.
            fastForwardWindow = 1.hours
        }
        val depsRepo = JobDependencyRepositoryImpl(database)
        val recurringRepo = RecurringJobRepositoryImpl(database)
        val jobEventsRepo = JobEventRepositoryImpl(database)
        val workersRepo = cs.trade.scheduler.storage.postgres.infrastructure.repositories.WorkerRepositoryImpl(database)
        scheduler = DefaultScheduler(
            storage = PostgresStorageProvider(
                jobs = jobs,
                outbox = outbox,
                jobDependencies = depsRepo,
                recurringJobs = recurringRepo,
                jobEvents = jobEventsRepo,
                workers = workersRepo,
                idempotencyLog = cs.trade.scheduler.storage.postgres.infrastructure.repositories.IdempotencyLogRepositoryImpl(database),
                jobRollups = cs.trade.scheduler.storage.postgres.infrastructure.repositories.JobRollupRepositoryImpl(database),
                jobTypePauses = cs.trade.scheduler.storage.postgres.infrastructure.repositories.JobTypePauseRepositoryImpl(database),
            ),
            database = database,
            config = coreConfig,
        )
        fastForward = FastForwardScheduledJobsUseCase(database, jobs, outbox, coreConfig)
    }

    @AfterAll
    fun tearDown() {
        runCatching { dataSource.close() }
        runCatching { postgres?.stop() }
    }

    @Test
    fun `scheduleAt within window goes ENQUEUED with delayed outbox row`() = runBlocking {
        val at = Clock.System.now() + 5.seconds

        val jobId = scheduler.scheduleAt(TestJob(n = 1), at = at, options = EnqueueOptions())

        val saved = jobs.findById(jobId)
        assertNotNull(saved)
        assertEquals(JobState.ENQUEUED, saved!!.state)
        assertNotNull(saved.scheduledAt, "scheduledAt must be persisted on scheduleAt")

        val row = outbox.findUnpublished(limit = 100).single { it.jobId == jobId }
        // delay ≈ 5000ms, allow generous slack for setup overhead.
        assertTrue(
            row.delayMs in 3_500..6_000,
            "delayMs should be ≈ 5000, got ${row.delayMs}",
        )
    }

    @Test
    fun `scheduleAt beyond window stays SCHEDULED with no outbox row`() = runBlocking {
        val at = Clock.System.now() + 2.hours  // window = 1h

        val jobId = scheduler.scheduleAt(TestJob(n = 2), at = at, options = EnqueueOptions())

        val saved = jobs.findById(jobId)
        assertNotNull(saved)
        assertEquals(JobState.SCHEDULED, saved!!.state)
        assertNotNull(saved.scheduledAt)

        assertTrue(
            outbox.findUnpublished(limit = 100).none { it.jobId == jobId },
            "Beyond-window scheduleAt must NOT write an outbox row",
        )
    }

    @Test
    fun `FastForwardScheduledJobsUseCase promotes due SCHEDULED to ENQUEUED+outbox`() = runBlocking {
        // Schedule a job 2h out — initially SCHEDULED (window = 1h).
        val farId = scheduler.scheduleAt(TestJob(n = 10), at = Clock.System.now() + 2.hours, options = EnqueueOptions())
        assertEquals(JobState.SCHEDULED, jobs.findById(farId)!!.state)
        assertTrue(outbox.findUnpublished(limit = 100).none { it.jobId == farId })

        // Bump window so this job now qualifies for promotion.
        coreConfig.fastForwardWindow = 24.hours
        try {
            val promoted = fastForward().getOrThrow()
            assertTrue(
                promoted >= 1,
                "Job at +2h should be promoted when window=24h (got $promoted, may include leftovers from other tests)",
            )

            val after = jobs.findById(farId)!!
            assertEquals(JobState.ENQUEUED, after.state)
            assertNull(after.lockedBy)
            assertNull(after.lockedUntil)
            // Version should have bumped from 0 → 1 via transitionState.
            assertEquals(1, after.version)

            val row = outbox.findUnpublished(limit = 100).single { it.jobId == farId }
            // Residual delay ≈ 2h. Allow ±5 minutes for setUp + test overhead.
            assertTrue(
                row.delayMs in (2.hours - 5.minutes).inWholeMilliseconds..(2.hours + 1.minutes).inWholeMilliseconds,
                "Residual delay should be ≈ 2h, got ${row.delayMs}ms",
            )
        } finally {
            coreConfig.fastForwardWindow = 1.hours
        }
    }
}
