@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package cs.trade.scheduler.storage.postgres

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import cs.trade.scheduler.shared.JobPriority
import cs.trade.scheduler.shared.JobState
import cs.trade.scheduler.storage.postgres.domain.models.Job
import cs.trade.scheduler.storage.postgres.infrastructure.repositories.JobRepositoryImpl
import kotlinx.coroutines.runBlocking
import org.flywaydb.core.Flyway
import org.jetbrains.exposed.v1.jdbc.Database
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.testcontainers.containers.PostgreSQLContainer
import kotlin.time.Clock
import kotlin.uuid.Uuid

/**
 * A finished job's progress bar must read as finished.
 *
 * Progress writes are throttled to one per second and the counting bar only forces a write on the
 * sample that reaches `total`, so a handler that returns having accounted for 312 of 314 items
 * leaves the last sample short. The row then says 99% under a green SUCCEEDED chip — which reads
 * as unfinished work. `finishTerminal` squares the fraction up on success; these tests pin down
 * that it does so ONLY on success, and that it never rewrites the handler's own counters.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ProgressCompletionIntegrationTest {

    companion object {
        private val externalUrl: String? = System.getenv("EXTERNAL_PG_URL")?.takeIf { it.isNotBlank() }
    }

    private var postgres: PostgreSQLContainer<*>? = null
    private lateinit var dataSource: HikariDataSource
    private lateinit var database: Database
    private lateinit var jobs: JobRepositoryImpl

    @BeforeAll
    fun setUp() {
        val jdbcUrl: String
        val pgUser: String
        val pgPass: String
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
    }

    @AfterAll
    fun tearDown() {
        runCatching { dataSource.close() }
        runCatching { postgres?.stop() }
    }

    /** A PROCESSING row whose handler last reported [progress] (and optionally counts). */
    private suspend fun processingJob(
        progress: Float?,
        succeeded: Long? = null,
        failed: Long? = null,
        total: Long? = null,
    ): Job {
        val now = Clock.System.now()
        val inserted = jobs.insert(
            Job(
                id = Uuid.random(),
                state = JobState.PROCESSING,
                queue = "default",
                priority = JobPriority(0),
                payloadType = "test.Progress",
                payloadJson = """{"n":1}""",
                scheduledAt = null,
                attempts = 1,
                maxAttempts = 3,
                timeoutSeconds = null,
                lockedBy = "test-node",
                lockedUntil = null,
                pendingDeps = 0,
                version = 0,
                idempotencyKey = null,
                targetNode = null,
                targetTag = null,
                progress = progress,
                progressMsg = null,
                progressUpdatedAt = now,
                startedAt = now,
                durationMs = null,
                cancelRequestedAt = null,
                cancelRequestedBy = null,
                contextJson = null,
                createdAt = now,
                updatedAt = now,
                progressSucceeded = succeeded,
                progressFailed = failed,
                progressTotal = total,
            ),
        )
        return inserted
    }

    @Test
    fun `success completes a bar left short by the throttle`() = runBlocking {
        // 312 of 314 accounted for: the tail samples never reached the DB.
        val job = processingJob(progress = 312f / 314f, succeeded = 312, failed = 0, total = 314)

        assertTrue(jobs.markSucceeded(job.id, expectedVersion = job.version))

        val after = jobs.findById(job.id)!!
        assertEquals(1f, after.progress)
        // The handler's tally is left alone — inflating it would invent work it never reported.
        assertEquals(312L, after.progressSucceeded)
        assertEquals(0L, after.progressFailed)
        assertEquals(314L, after.progressTotal)
    }

    @Test
    fun `a job that never reported progress stays without a bar`() = runBlocking {
        val job = processingJob(progress = null)

        assertTrue(jobs.markSucceeded(job.id, expectedVersion = job.version))

        // Must stay null: a 100% bar on a job that never had one would invent a progress UI
        // for every plain handler in the system.
        assertNull(jobs.findById(job.id)!!.progress)
    }

    @Test
    fun `failure keeps the fraction where it stopped`() = runBlocking {
        val job = processingJob(progress = 0.6f, succeeded = 60, failed = 3, total = 105)

        assertTrue(jobs.markFailed(job.id, expectedVersion = job.version, errorMsg = "boom"))

        // How far it got before dying is the diagnostic — completing it would erase that.
        val after = jobs.findById(job.id)!!
        assertEquals(0.6f, after.progress)
        assertEquals(60L, after.progressSucceeded)
    }

    @Test
    fun `cancellation keeps the fraction where it stopped`() = runBlocking {
        val job = processingJob(progress = 0.25f)

        assertTrue(jobs.markCancelled(job.id, expectedVersion = job.version, actor = "operator"))

        assertEquals(0.25f, jobs.findById(job.id)!!.progress)
    }
}
