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
import kotlin.time.Duration.Companion.minutes
import kotlin.uuid.Uuid

/**
 * [JobRepositoryImpl.findLatestRunsByRecurringIds] — the query behind the Recurring screen's
 * "is it running, and what's its progress" column and its row click-through.
 *
 * The selection rule is the whole point: a definition with something in flight must report THAT
 * run, not its newest finished one, or the screen would link an operator to yesterday's job while
 * today's is still going.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RecurringRunsIntegrationTest {

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

    private fun job(
        recurringId: String?,
        state: JobState,
        createdMinutesAgo: Int,
        progress: Float? = null,
    ): Job {
        val now = Clock.System.now()
        return Job(
            id = Uuid.random(),
            state = state,
            queue = "default",
            priority = JobPriority(0),
            payloadType = "test.Recurring",
            payloadJson = """{"n":1}""",
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
            progress = progress,
            progressMsg = null,
            progressUpdatedAt = null,
            startedAt = null,
            durationMs = null,
            cancelRequestedAt = null,
            cancelRequestedBy = null,
            contextJson = null,
            createdAt = now - createdMinutesAgo.minutes,
            updatedAt = now - createdMinutesAgo.minutes,
            recurringId = recurringId,
        )
    }

    @Test
    fun `live run wins over a newer finished one`() = runBlocking {
        val id = "live-${Uuid.random()}"
        // The finished job is NEWER, so a plain "latest by created_at" would pick it — the live
        // one must still win.
        jobs.insert(job(id, JobState.SUCCEEDED, createdMinutesAgo = 1))
        val running = jobs.insert(job(id, JobState.PROCESSING, createdMinutesAgo = 30, progress = 0.5f))

        val runs = jobs.findLatestRunsByRecurringIds(listOf(id))

        val run = runs[id]
        assertEquals(running.id, run?.jobId)
        assertEquals(JobState.PROCESSING, run?.state)
        assertEquals(0.5f, run?.progress)
        assertTrue(run?.isLive == true)
    }

    @Test
    fun `falls back to the most recent finished run`() = runBlocking {
        val id = "finished-${Uuid.random()}"
        jobs.insert(job(id, JobState.SUCCEEDED, createdMinutesAgo = 90))
        val newest = jobs.insert(job(id, JobState.FAILED, createdMinutesAgo = 5))

        val run = jobs.findLatestRunsByRecurringIds(listOf(id))[id]

        assertEquals(newest.id, run?.jobId)
        assertEquals(JobState.FAILED, run?.state)
        assertTrue(run?.isLive == false)
    }

    @Test
    fun `resolves each definition independently in one call`() = runBlocking {
        val a = "batch-a-${Uuid.random()}"
        val b = "batch-b-${Uuid.random()}"
        val neverRan = "batch-none-${Uuid.random()}"
        val aRunning = jobs.insert(job(a, JobState.PROCESSING, createdMinutesAgo = 10))
        val bDone = jobs.insert(job(b, JobState.SUCCEEDED, createdMinutesAgo = 10))

        val runs = jobs.findLatestRunsByRecurringIds(listOf(a, b, neverRan))

        assertEquals(aRunning.id, runs[a]?.jobId)
        assertEquals(bDone.id, runs[b]?.jobId)
        // A definition that never fired is simply absent — the screen renders "never run".
        assertNull(runs[neverRan])
    }

    @Test
    fun `ignores one-off jobs`() = runBlocking {
        val id = "isolated-${Uuid.random()}"
        jobs.insert(job(recurringId = null, state = JobState.PROCESSING, createdMinutesAgo = 1))

        assertTrue(jobs.findLatestRunsByRecurringIds(listOf(id)).isEmpty())
    }

    @Test
    fun `empty id list short-circuits`() = runBlocking {
        assertTrue(jobs.findLatestRunsByRecurringIds(emptyList()).isEmpty())
    }
}
