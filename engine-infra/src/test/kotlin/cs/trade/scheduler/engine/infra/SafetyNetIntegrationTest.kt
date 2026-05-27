@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package cs.trade.scheduler.engine.infra

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import cs.trade.scheduler.engine.infra.domain.usecases.RecoverOrphanedJobsUseCase
import cs.trade.scheduler.shared.JobPriority
import cs.trade.scheduler.shared.JobState
import cs.trade.scheduler.storage.postgres.domain.models.Job
import cs.trade.scheduler.storage.postgres.infrastructure.repositories.JobRepositoryImpl
import cs.trade.scheduler.storage.postgres.infrastructure.repositories.OutboxRepositoryImpl
import kotlinx.coroutines.runBlocking
import org.flywaydb.core.Flyway
import org.jetbrains.exposed.v1.jdbc.Database
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.testcontainers.containers.PostgreSQLContainer
import kotlin.time.Clock
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.uuid.Uuid

/**
 * Verifies the orphan-recovery path end-to-end against a real PG (no Rabbit needed —
 * we observe the outbox row directly):
 *
 *   1. Insert a PROCESSING row with `locked_until` 5 minutes in the past — simulates
 *      a worker that died mid-execution.
 *   2. Invoke [RecoverOrphanedJobsUseCase].
 *   3. Assert: state flipped to AWAITING_RETRY, lock cleared, version bumped,
 *      one unpublished outbox row appeared.
 *
 * Bypasses Koin for a tighter test — we construct the repositories + UseCase directly
 * so the assertions own all the wiring. Koin-level wiring is covered by
 * [OutboxPublisherIntegrationTest] which loads `schedulerInfraModule()` whole.
 *
 * **PG provisioning.** Honours `EXTERNAL_PG_URL` for the shared scheduler-test-pg
 * setup; falls back to Testcontainers when absent. Manual lifecycle so the env
 * override can short-circuit Docker.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SafetyNetIntegrationTest {

    private companion object {
        private val externalUrl: String? = System.getenv("EXTERNAL_PG_URL")?.takeIf { it.isNotBlank() }
    }

    private lateinit var dataSource: HikariDataSource
    private lateinit var database: Database
    private lateinit var jobs: JobRepositoryImpl
    private lateinit var outbox: OutboxRepositoryImpl
    private lateinit var useCase: RecoverOrphanedJobsUseCase
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
        useCase = RecoverOrphanedJobsUseCase(database, jobs, outbox)
    }

    @BeforeEach
    fun cleanTables() {
        // Both tests insert PROCESSING rows that the OTHER test's `findOrphaned` would
        // pick up under a shared `scheduler-test-pg`. Truncating job + outbox between
        // tests keeps the assertions ("exactly 1 orphan", "no live row in orphans")
        // load-bearing instead of degenerating into "1 + however many leaked".
        runCatching {
            dataSource.connection.use { conn ->
                conn.createStatement().use { it.execute("TRUNCATE job, outbox RESTART IDENTITY CASCADE") }
            }
        }
    }

    @AfterAll
    fun tearDown() {
        runCatching { dataSource.close() }
        runCatching { postgres?.stop() }
    }

    @Test
    fun `stale PROCESSING row gets recovered into AWAITING_RETRY with an outbox row`() = runBlocking {
        val now = Clock.System.now()
        val orphan = sampleJob(
            id = Uuid.random(),
            state = JobState.PROCESSING,
            queue = "default",
            lockedBy = "dead-worker",
            lockedUntil = now - 5.minutes,  // expired 5 minutes ago
            attempts = 1,
            version = 1,
            startedAtMinutesAgo = 10,
        )
        jobs.insert(orphan)

        // Sanity: row is visible as orphan before recovery.
        val orphansBefore = jobs.findOrphaned(limit = 10)
        assertEquals(1, orphansBefore.size, "Inserted PROCESSING+past-lock row should be findOrphaned")
        assertEquals(orphan.id, orphansBefore[0].id)

        val recovered = useCase().getOrThrow()
        assertEquals(1, recovered)

        // After recovery: state flipped, lock cleared, version bumped, scheduled_at set.
        val after = jobs.findById(orphan.id)
        assertNotNull(after)
        assertEquals(JobState.AWAITING_RETRY, after!!.state)
        assertNull(after.lockedBy)
        assertNull(after.lockedUntil)
        assertEquals(2, after.version, "version must bump from 1 → 2 on markForRetry")
        assertNotNull(after.scheduledAt, "scheduledAt should be set (≈ now + 0)")
        // attempts NOT bumped here — pickup does that on the next attempt.
        assertEquals(1, after.attempts)

        // And exactly one unpublished outbox row for this job.
        val pending = outbox.findUnpublished(limit = 10)
        assertEquals(1, pending.size)
        assertEquals(orphan.id, pending[0].jobId)
        assertEquals("default", pending[0].routingKey)
        assertEquals(0, pending[0].delayMs)
        assertNull(pending[0].publishedAt)
    }

    @Test
    fun `non-expired PROCESSING rows are NOT picked up by the safety net`() = runBlocking {
        val now = Clock.System.now()
        val live = sampleJob(
            id = Uuid.random(),
            state = JobState.PROCESSING,
            queue = "default",
            lockedBy = "alive-worker",
            lockedUntil = now + 1.hours,  // far in the future
            attempts = 1,
            version = 1,
            startedAtMinutesAgo = 1,
        )
        jobs.insert(live)

        val visibleOrphans = jobs.findOrphaned(limit = 10)
        assertTrue(
            visibleOrphans.none { it.id == live.id },
            "Live PROCESSING row must not be returned by findOrphaned",
        )
    }

    private fun sampleJob(
        id: Uuid,
        state: JobState,
        queue: String,
        lockedBy: String?,
        lockedUntil: kotlin.time.Instant?,
        attempts: Int,
        version: Int,
        startedAtMinutesAgo: Long,
    ): Job {
        val now = Clock.System.now()
        return Job(
            id = id,
            state = state,
            queue = queue,
            priority = JobPriority(0),
            payloadType = "test.RecoveryJob",
            payloadJson = """{"_type":"test.RecoveryJob"}""",
            scheduledAt = null,
            attempts = attempts,
            maxAttempts = 3,
            timeoutSeconds = null,
            lockedBy = lockedBy,
            lockedUntil = lockedUntil,
            pendingDeps = 0,
            version = version,
            idempotencyKey = null,
            targetNode = null,
            targetTag = null,
            progress = null,
            progressMsg = null,
            progressUpdatedAt = null,
            startedAt = now - startedAtMinutesAgo.minutes,
            durationMs = null,
            cancelRequestedAt = null,
            cancelRequestedBy = null,
            contextJson = null,
            createdAt = now,
            updatedAt = now,
        )
    }
}
