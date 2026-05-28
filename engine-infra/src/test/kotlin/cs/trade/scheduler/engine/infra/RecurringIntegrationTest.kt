@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package cs.trade.scheduler.engine.infra

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import cs.trade.scheduler.core.backend.RecurringDefinition
import cs.trade.scheduler.core.backend.Scheduler
import cs.trade.scheduler.core.backend.SchedulerCoreConfig
import cs.trade.scheduler.core.backend.handler.Job
import cs.trade.scheduler.engine.infra.domain.usecases.FireDueRecurringJobsUseCase
import cs.trade.scheduler.shared.JobState
import cs.trade.scheduler.shared.MisfirePolicy
import cs.trade.scheduler.storage.postgres.infrastructure.PostgresStorageProvider
import cs.trade.scheduler.storage.postgres.infrastructure.repositories.JobDependencyRepositoryImpl
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

/**
 * Covers `scheduler.recurring()` registration + [FireDueRecurringJobsUseCase] firing
 * path without spinning up Rabbit. The fired `job` row + outbox row are observed via
 * repositories directly.
 *
 * **PG provisioning.** Honours `EXTERNAL_PG_URL` for the shared scheduler-test-pg
 * setup; falls back to Testcontainers when absent. Manual lifecycle so the env
 * override can short-circuit Docker.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RecurringIntegrationTest {

    @Serializable
    data class DailyReport(val tenantId: Long) : Job

    private companion object {
        private val externalUrl: String? = System.getenv("EXTERNAL_PG_URL")?.takeIf { it.isNotBlank() }
    }

    private lateinit var dataSource: HikariDataSource
    private lateinit var database: Database
    private lateinit var jobs: JobRepositoryImpl
    private lateinit var outbox: OutboxRepositoryImpl
    private lateinit var recurring: RecurringJobRepositoryImpl
    private lateinit var coreConfig: SchedulerCoreConfig
    private lateinit var scheduler: DefaultScheduler
    private lateinit var fireDue: FireDueRecurringJobsUseCase
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
        recurring = RecurringJobRepositoryImpl(database)
        val deps = JobDependencyRepositoryImpl(database)
        val jobEventsRepo = cs.trade.scheduler.storage.postgres.infrastructure.repositories.JobEventRepositoryImpl(database)
        val workersRepo = cs.trade.scheduler.storage.postgres.infrastructure.repositories.WorkerRepositoryImpl(database)
        coreConfig = SchedulerCoreConfig().apply { nodeId = "test-recurring" }
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
            config = coreConfig,
        )
        fireDue = FireDueRecurringJobsUseCase(database, recurring, jobs, outbox, coreConfig)
    }

    @AfterAll
    fun tearDown() {
        runCatching { dataSource.close() }
        runCatching { postgres?.stop() }
    }

    @Test
    fun `recurring registration writes row with computed nextTriggerAt`() = runBlocking {
        val id = "daily-billing-${System.nanoTime()}"
        scheduler.recurring(
            RecurringDefinition(
                id = id,
                cron = "0 9 * * *",         // 09:00 UTC every day
                job = DailyReport(tenantId = 1),
                queue = "default",
                priority = 5,
            ),
        )

        val row = recurring.findById(id)
        assertNotNull(row, "Recurring row should be persisted")
        assertEquals("0 9 * * *", row!!.cron)
        assertEquals(MisfirePolicy.CATCH_UP_ONE, row.misfirePolicy)
        assertEquals(5, row.priority)
        assertEquals(true, row.enabled)
        assertNull(row.lastTriggeredAt)
        assertTrue(
            row.nextTriggerAt > kotlin.time.Clock.System.now(),
            "nextTriggerAt must be in the future, got ${row.nextTriggerAt}",
        )
        // payload was serialised through Json (no class discriminator since it's a single class).
        // PG normalises JSONB with whitespace after colons — match with regex so the
        // assertion is stable across the in-memory compact JSON and the PG round-trip.
        assertTrue(
            Regex("\"tenantId\"\\s*:\\s*1\\b").containsMatchIn(row.payloadJson),
            "payload_json must contain tenantId=1: ${row.payloadJson}",
        )
    }

    @Test
    fun `fireDue picks up rows with past nextTriggerAt and creates job+outbox`() = runBlocking {
        val id = "due-now-${System.nanoTime()}"
        // Register via the scheduler — gets a future nextTriggerAt.
        scheduler.recurring(
            RecurringDefinition(
                id = id,
                cron = "* * * * *",         // every minute
                job = DailyReport(tenantId = 99),
                queue = "default",
            ),
        )

        // Force the row "due" by hand — set nextTriggerAt 1 minute in the past via upsert.
        val original = recurring.findById(id)!!
        recurring.upsert(
            original.copy(nextTriggerAt = kotlin.time.Clock.System.now() - kotlin.time.Duration.parse("PT1M")),
        )

        val fired = fireDue().getOrThrow()
        assertTrue(fired >= 1, "Past-due recurring should fire at least once, got $fired")

        // Recurring row should have advanced: nextTriggerAt > now, lastTriggeredAt now set.
        val after = recurring.findById(id)!!
        assertNotNull(after.lastTriggeredAt, "lastTriggeredAt must be stamped after fire")
        assertTrue(
            after.nextTriggerAt > kotlin.time.Clock.System.now(),
            "nextTriggerAt must advance into the future after firing",
        )

        // One fresh `job` row with this recurring's payload type, in ENQUEUED.
        // Findability via outbox: pick any unpublished row whose payload_type matches.
        val pendingForThis = outbox.findUnpublished(limit = 100)
            .mapNotNull { jobs.findById(it.jobId) }
            .filter { it.payloadType == DailyReport::class.qualifiedName }
        assertTrue(
            pendingForThis.any { it.payloadJson.contains("\"tenantId\":99") },
            "Should find at least one ENQUEUED job with this recurring's payload",
        )
        pendingForThis.firstOrNull { it.payloadJson.contains("\"tenantId\":99") }?.let { job ->
            assertEquals(JobState.ENQUEUED, job.state)
            assertEquals("default", job.queue)
        }
    }

    @Test
    fun `CATCH_UP_ALL fires one job per missed slot while CATCH_UP_ONE collapses to one`() = runBlocking {
        // Unique payload markers so the counts isolate from other tests sharing this PG.
        val tenantAll = System.nanoTime()
        val tenantOne = tenantAll + 1
        val idAll = "catchup-all-$tenantAll"
        val idOne = "catchup-one-$tenantAll"

        // Both: every-minute cron, identical 5-minute backlog. Only the policy differs.
        scheduler.recurring(
            RecurringDefinition(
                id = idAll, cron = "* * * * *", job = DailyReport(tenantId = tenantAll),
                misfirePolicy = MisfirePolicy.CATCH_UP_ALL,
            ),
        )
        scheduler.recurring(
            RecurringDefinition(
                id = idOne, cron = "* * * * *", job = DailyReport(tenantId = tenantOne),
                misfirePolicy = MisfirePolicy.CATCH_UP_ONE,
            ),
        )

        // Force both overdue at a REAL cron slot: a minute boundary 5 minutes in the past
        // (every-minute cron fires on minute boundaries, so this is a genuine missed slot).
        val nowMinute = kotlin.time.Instant.fromEpochSeconds(kotlin.time.Clock.System.now().epochSeconds / 60 * 60)
        val overdue = nowMinute - kotlin.time.Duration.parse("PT5M")
        recurring.upsert(recurring.findById(idAll)!!.copy(nextTriggerAt = overdue))
        recurring.upsert(recurring.findById(idOne)!!.copy(nextTriggerAt = overdue))

        fireDue().getOrThrow()

        val createdAll = countCreatedJobs(tenantAll)
        val createdOne = countCreatedJobs(tenantOne)

        // CATCH_UP_ONE collapses any backlog to a single job.
        assertEquals(1, createdOne, "CATCH_UP_ONE must fire exactly once regardless of backlog")
        // CATCH_UP_ALL fires one per missed minute. A 5-minute backlog yields 6 slots
        // (boundary-5 .. boundary inclusive); allow +1 for a minute rollover mid-test.
        assertTrue(
            createdAll >= 5,
            "CATCH_UP_ALL must fire one job per missed slot (>=5 for a 5-min backlog), got $createdAll",
        )
        assertTrue(createdAll > createdOne, "CATCH_UP_ALL must out-fire CATCH_UP_ONE on the same backlog")

        // Both rows resume in the future (no longer due).
        val now = kotlin.time.Clock.System.now()
        assertTrue(recurring.findById(idAll)!!.nextTriggerAt > now, "CATCH_UP_ALL row must advance past now")
        assertTrue(recurring.findById(idOne)!!.nextTriggerAt > now, "CATCH_UP_ONE row must advance past now")
    }

    /** Count ENQUEUED jobs carrying this recurring's unique payload marker, via the outbox. */
    private suspend fun countCreatedJobs(tenantId: Long): Int =
        outbox.findUnpublished(limit = 1000)
            .mapNotNull { jobs.findById(it.jobId) }
            .count {
                it.payloadType == DailyReport::class.qualifiedName &&
                    Regex("\"tenantId\"\\s*:\\s*$tenantId\\b").containsMatchIn(it.payloadJson)
            }

    @Test
    fun `disabled recurring is not picked up by fireDue`() = runBlocking {
        val id = "disabled-${System.nanoTime()}"
        scheduler.recurring(
            RecurringDefinition(
                id = id,
                cron = "* * * * *",
                job = DailyReport(tenantId = 7),
            ),
        )
        // Mark row in the past + disable.
        val original = recurring.findById(id)!!
        recurring.upsert(original.copy(nextTriggerAt = kotlin.time.Clock.System.now() - kotlin.time.Duration.parse("PT1M")))
        recurring.disable(id)

        fireDue().getOrThrow()

        val after = recurring.findById(id)!!
        assertNull(after.lastTriggeredAt, "Disabled row must not fire")
        assertEquals(false, after.enabled)
    }

    @Test
    fun `re-registering with same id preserves last_triggered_at`() = runBlocking {
        val id = "preserve-${System.nanoTime()}"
        scheduler.recurring(
            RecurringDefinition(
                id = id,
                cron = "0 0 * * *",
                job = DailyReport(tenantId = 1),
            ),
        )
        val original = recurring.findById(id)!!
        // Simulate one firing.
        recurring.markFiredAndScheduleNext(
            id = id,
            expectedLastTriggeredAt = null,
            firedAt = kotlin.time.Clock.System.now(),
            next = original.nextTriggerAt,
        )
        val firedRow = recurring.findById(id)!!
        assertNotNull(firedRow.lastTriggeredAt)

        // Re-register with a different cron — lastTriggeredAt should survive.
        scheduler.recurring(
            RecurringDefinition(
                id = id,
                cron = "30 12 * * *",         // new schedule
                job = DailyReport(tenantId = 1),
            ),
        )
        val reregistered = recurring.findById(id)!!
        assertEquals("30 12 * * *", reregistered.cron, "cron should update")
        assertEquals(
            firedRow.lastTriggeredAt,
            reregistered.lastTriggeredAt,
            "lastTriggeredAt must be preserved across re-registration",
        )
    }
}
