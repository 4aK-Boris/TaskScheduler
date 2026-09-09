@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package cs.trade.scheduler.engine.infra

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import cs.trade.scheduler.engine.infra.domain.usecases.RecoverStuckEnqueuedJobsUseCase
import cs.trade.scheduler.shared.JobPriority
import cs.trade.scheduler.shared.JobState
import cs.trade.scheduler.storage.postgres.domain.models.Job
import cs.trade.scheduler.storage.postgres.infrastructure.repositories.JobRepositoryImpl
import cs.trade.scheduler.storage.postgres.infrastructure.repositories.OutboxRepositoryImpl
import kotlinx.coroutines.delay
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
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.uuid.Uuid

/**
 * Covers the second salvage path: a job whose *broker message* was lost sits in ENQUEUED
 * forever, because an ENQUEUED row has no lock for [SafetyNetIntegrationTest]'s orphan
 * recovery to expire. Production incident 2026-09-09 — 18 `q.default` jobs published during
 * a worker restart never arrived, and `SKIP` overlap kept their recurring channels down for
 * 74 minutes.
 *
 * The distinguishing signal is overtaking, and both branches are pinned here: a stale job
 * that newer jobs of its queue have already run past is re-published, one that is merely
 * waiting its turn is left alone.
 *
 * `insert` stamps `created_at`/`updated_at` server-side, so staleness is exercised through
 * the [Duration.ZERO] threshold rather than by back-dating rows — the ordering between the
 * two inserts is what the assertions actually rest on.
 *
 * **PG provisioning.** Honours `EXTERNAL_PG_URL` like its sibling; falls back to
 * Testcontainers when absent.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class StuckEnqueuedRecoveryIntegrationTest {

    private companion object {
        private val externalUrl: String? = System.getenv("EXTERNAL_PG_URL")?.takeIf { it.isNotBlank() }
    }

    private lateinit var dataSource: HikariDataSource
    private lateinit var database: Database
    private lateinit var jobs: JobRepositoryImpl
    private lateinit var outbox: OutboxRepositoryImpl
    private lateinit var useCase: RecoverStuckEnqueuedJobsUseCase
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

        Flyway.configure().dataSource(dataSource).locations("classpath:scheduler/migration").load().migrate()

        database = Database.connect(dataSource)
        jobs = JobRepositoryImpl(database)
        outbox = OutboxRepositoryImpl(database)
        useCase = RecoverStuckEnqueuedJobsUseCase(database, jobs, outbox)
    }

    @BeforeEach
    fun cleanTables() {
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
    fun `overtaken ENQUEUED row gets its message re-published, state untouched`() = runBlocking {
        // Take the row as stored: `insert` stamps created_at/updated_at itself, so the
        // in-memory template's timestamps are not what the assertions must compare against.
        val stuck = jobs.insert(enqueuedJob(queue = "default"))
        delay(timeMillis = 10)                       // keep created_at strictly ordered
        // A newer job of the same queue that already ran: proof the queue moved past `stuck`.
        jobs.insert(startedJob(queue = "default"))

        val republished = useCase(staleFor = Duration.ZERO).getOrThrow()
        assertEquals(1, republished)

        // Row stays ENQUEUED — recovery replaces a lost message, it does not re-run anything.
        val after = jobs.findById(stuck.id)
        assertNotNull(after)
        assertEquals(JobState.ENQUEUED, after!!.state)
        assertEquals(stuck.version + 1, after.version, "touch must bump version so a racing pickup wins")
        assertTrue(after.updatedAt > stuck.updatedAt, "updated_at must move so the next scan skips this row")

        val pending = outbox.findUnpublished(limit = 10)
        assertEquals(1, pending.size)
        assertEquals(stuck.id, pending[0].jobId)
        assertEquals("default", pending[0].routingKey)
        assertEquals(0, pending[0].delayMs)
        assertNull(pending[0].publishedAt)
    }

    @Test
    fun `ENQUEUED row that is merely waiting its turn is left alone`() = runBlocking {
        // Nothing newer has started on this queue — the message may still be in the broker,
        // exactly the q.heavy case (concurrency 1, jobs queue up behind a long predecessor).
        jobs.insert(enqueuedJob(queue = "heavy"))

        val republished = useCase(staleFor = Duration.ZERO).getOrThrow()

        assertEquals(0, republished)
        assertTrue(outbox.findUnpublished(limit = 10).isEmpty(), "no outbox row for a job that is simply queued")
    }

    @Test
    fun `higher-priority newer job does not count as overtaking`() = runBlocking {
        // Priority 9 legitimately jumps the queue ahead of priority 0, so its head start says
        // nothing about the lower-priority message still waiting behind it.
        jobs.insert(enqueuedJob(queue = "default", priority = 0))
        delay(timeMillis = 10)
        jobs.insert(startedJob(queue = "default", priority = 9))

        val republished = useCase(staleFor = Duration.ZERO).getOrThrow()

        assertEquals(0, republished)
    }

    @Test
    fun `fresh ENQUEUED row is below the staleness threshold`() = runBlocking {
        jobs.insert(enqueuedJob(queue = "default"))
        delay(timeMillis = 10)
        jobs.insert(startedJob(queue = "default"))

        val republished = useCase(staleFor = 5.minutes).getOrThrow()

        assertEquals(0, republished, "a job enqueued seconds ago is not evidence of a lost message")
    }

    private fun enqueuedJob(queue: String, priority: Int = 0): Job =
        sampleJob(state = JobState.ENQUEUED, queue = queue, priority = priority, startedAt = null)

    private fun startedJob(queue: String, priority: Int = 0): Job =
        sampleJob(state = JobState.PROCESSING, queue = queue, priority = priority, startedAt = Clock.System.now())

    private fun sampleJob(
        state: JobState,
        queue: String,
        priority: Int,
        startedAt: kotlin.time.Instant?,
    ): Job {
        val now = Clock.System.now()
        return Job(
            id = Uuid.random(),
            state = state,
            queue = queue,
            priority = JobPriority(priority),
            payloadType = "test.StuckEnqueuedJob",
            payloadJson = """{"_type":"test.StuckEnqueuedJob"}""",
            scheduledAt = null,
            attempts = 0,
            maxAttempts = 3,
            timeoutSeconds = null,
            lockedBy = null,
            lockedUntil = null,
            pendingDeps = 0,
            version = 0,
            idempotencyKey = null,
            targetNode = null,
            targetTag = null,
            progress = null,
            progressMsg = null,
            progressUpdatedAt = null,
            startedAt = startedAt,
            durationMs = null,
            cancelRequestedAt = null,
            cancelRequestedBy = null,
            contextJson = null,
            createdAt = now,
            updatedAt = now,
        )
    }
}
