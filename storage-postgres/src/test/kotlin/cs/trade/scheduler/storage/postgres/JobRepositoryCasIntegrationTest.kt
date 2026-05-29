@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package cs.trade.scheduler.storage.postgres

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import cs.trade.scheduler.core.backend.SchedulerCoreConfig
import cs.trade.scheduler.core.backend.handler.Job
import cs.trade.scheduler.shared.JobState
import cs.trade.scheduler.shared.RetryMode
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
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import org.flywaydb.core.Flyway
import org.jetbrains.exposed.v1.jdbc.Database
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.testcontainers.containers.PostgreSQLContainer
import kotlin.uuid.Uuid

/**
 * CAS semantics for [JobRepositoryImpl]. Every method here that bumps `version` is the
 * source-of-truth for one of the state-machine transitions, so we exercise both the
 * happy path AND the conflict path against a real Postgres (Testcontainers, not H2 — PG
 * has subtle `UPDATE` rowcount behaviour around partial indexes and we want the real one).
 *
 * Covered operations:
 *   - updateRouting   — reroute (new in Phase 2 operability batch)
 *   - pickup          — worker pickup race (two callers, only one wins)
 *   - manualRetry     — FAILED → ENQUEUED, version + state gate
 *   - releaseProcessingLock — PROCESSING → ENQUEUED, version + state gate
 *   - markSucceeded   — version conflict on terminal transition
 */
// Lifecycle-managed manually (no @Testcontainers / @Container) so EXTERNAL_PG_URL can
// bypass testcontainers entirely on machines where Docker Desktop blocks the daemon API
// for non-CLI clients.
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class JobRepositoryCasIntegrationTest {

    @Serializable
    data class Noop(val n: Long) : Job

    companion object {
        // When EXTERNAL_PG_URL is set, the test connects to an already-running PG (the
        // user's own `docker run postgres:16-alpine` on the dev machine) and never spins
        // up a container. Flyway migrate is idempotent so re-runs against the same DB
        // are safe; each test creates fresh jobs with random UUIDs.
        private val externalUrl: String? = System.getenv("EXTERNAL_PG_URL")?.takeIf { it.isNotBlank() }
    }

    // Only constructed when EXTERNAL_PG_URL is absent. Lazy + nullable so we don't pay
    // the Docker probe cost on machines that use the external PG path.
    private var postgres: PostgreSQLContainer<*>? = null

    private lateinit var dataSource: HikariDataSource
    private lateinit var database: Database
    private lateinit var jobs: JobRepositoryImpl
    private lateinit var jobEvents: JobEventRepositoryImpl
    private lateinit var scheduler: DefaultScheduler

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
            // Two parallel-pickup test wants real connection-level concurrency.
            maximumPoolSize = 8
            addDataSourceProperty("stringtype", "unspecified")
        })
        Flyway.configure().dataSource(dataSource).load().migrate()
        database = Database.connect(dataSource)
        jobEvents = JobEventRepositoryImpl(database)
        // Pass jobEvents in so MANUAL_REROUTE / MANUAL_RETRY rows actually get recorded;
        // CancelIntegrationTest skips this because it doesn't assert on the audit log.
        jobs = JobRepositoryImpl(database, events = jobEvents)
        val outbox = OutboxRepositoryImpl(database)
        scheduler = DefaultScheduler(
            storage = PostgresStorageProvider(
                jobs = jobs,
                outbox = outbox,
                jobDependencies = JobDependencyRepositoryImpl(database),
                recurringJobs = RecurringJobRepositoryImpl(database),
                jobEvents = jobEvents,
                workers = WorkerRepositoryImpl(database),
                idempotencyLog = IdempotencyLogRepositoryImpl(database),
                jobRollups = JobRollupRepositoryImpl(database),
                jobTypePauses = JobTypePauseRepositoryImpl(database),
            ),
            database = database,
            config = SchedulerCoreConfig().apply { nodeId = "test-cas" },
        )
    }

    @AfterAll
    fun tearDown() {
        runCatching { dataSource.close() }
        // No-op when externalUrl was used.
        runCatching { postgres?.stop() }
    }

    // --- updateRouting ------------------------------------------------------------------

    @Test
    fun `updateRouting on ENQUEUED writes targetNode-targetTag, bumps version, records MANUAL_REROUTE`() = runBlocking {
        val jobId = scheduler.enqueue(Noop(1))
        val before = jobs.findById(jobId)!!
        assertEquals(JobState.ENQUEUED, before.state)
        assertNull(before.targetNode)
        assertNull(before.targetTag)

        val ok = jobs.updateRouting(
            jobId = jobId,
            expectedVersion = before.version,
            targetNode = "node-a",
            targetTag = "high-mem",
            actor = "alice",
        )
        assertTrue(ok)

        val after = jobs.findById(jobId)!!
        assertEquals("node-a", after.targetNode)
        assertEquals("high-mem", after.targetTag)
        assertEquals(before.version + 1, after.version)

        val reroute = jobEvents.findByJobId(jobId).single { it.eventType == "MANUAL_REROUTE" }
        assertEquals("alice", reroute.actor)
    }

    @Test
    fun `updateRouting with stale version returns false and leaves row untouched`() = runBlocking {
        val jobId = scheduler.enqueue(Noop(2))
        val initial = jobs.findById(jobId)!!

        // Concurrent reroute bumps the row to version + 1 — second call sees stale CAS.
        assertTrue(jobs.updateRouting(jobId, initial.version, "node-a", null, "alice"))

        val staleAttempt = jobs.updateRouting(
            jobId = jobId,
            expectedVersion = initial.version,   // stale: row is at initial.version + 1
            targetNode = "node-b",
            targetTag = "ssd",
            actor = "bob",
        )
        assertFalse(staleAttempt)

        val after = jobs.findById(jobId)!!
        assertEquals("node-a", after.targetNode)        // alice's value, not bob's
        assertNull(after.targetTag)
        assertEquals(initial.version + 1, after.version) // exactly one bump

        // No spurious second MANUAL_REROUTE row from bob's lost UPDATE.
        val rerouteCount = jobEvents.findByJobId(jobId).count { it.eventType == "MANUAL_REROUTE" }
        assertEquals(1, rerouteCount)
    }

    @Test
    fun `updateRouting on terminal row returns false`() = runBlocking {
        val jobId = scheduler.enqueue(Noop(3))
        val before = jobs.findById(jobId)!!
        // Force terminal — transitionState honours version, no side effects on targets.
        assertTrue(
            jobs.transitionState(
                id = jobId,
                expectedVersion = before.version,
                newState = JobState.SUCCEEDED,
                lockedBy = null,
                lockedUntilMillis = null,
            ),
        )

        val terminalVersion = jobs.findById(jobId)!!.version
        val ok = jobs.updateRouting(jobId, terminalVersion, "node-a", null, "alice")
        assertFalse(ok)

        val after = jobs.findById(jobId)!!
        assertNull(after.targetNode)
        assertEquals(JobState.SUCCEEDED, after.state)
    }

    // --- pickup race --------------------------------------------------------------------

    @Test
    fun `pickup with two concurrent workers — exactly one wins, the other gets null`() = runBlocking {
        val jobId = scheduler.enqueue(Noop(10))

        // Launch two pickups in parallel on the same ENQUEUED row. PG row-level locking
        // inside UPDATE makes one CAS succeed and the other see rows=0 → null.
        val (a, b) = coroutineScope {
            val ra = async { jobs.pickup(jobId, nodeId = "worker-a", lockDurationMillis = 60_000) }
            val rb = async { jobs.pickup(jobId, nodeId = "worker-b", lockDurationMillis = 60_000) }
            awaitAll(ra, rb)
        }

        val winner = listOfNotNull(a, b)
        assertEquals(1, winner.size, "exactly one pickup must win, got: a=$a b=$b")
        assertEquals(JobState.PROCESSING, winner.single().state)
        assertEquals(1, winner.single().attempts)
        assertNotNull(winner.single().lockedBy)

        // A second sequential pickup on a now-PROCESSING row also returns null.
        val third = jobs.pickup(jobId, nodeId = "worker-c", lockDurationMillis = 60_000)
        assertNull(third)
    }

    @Test
    fun `pickup on terminal row returns null without changing state`() = runBlocking {
        val jobId = scheduler.enqueue(Noop(11))
        val v0 = jobs.findById(jobId)!!.version
        assertTrue(jobs.transitionState(jobId, v0, JobState.CANCELLED, null, null))

        val result = jobs.pickup(jobId, "worker-a", 60_000)
        assertNull(result)
        assertEquals(JobState.CANCELLED, jobs.findById(jobId)!!.state)
    }

    // --- manualRetry --------------------------------------------------------------------

    @Test
    fun `manualRetry on FAILED resets attempts, clears lock, bumps version, records MANUAL_RETRY`() = runBlocking {
        val jobId = scheduler.enqueue(Noop(20))

        // ENQUEUED → PROCESSING → FAILED via the real worker primitives, so attempts is
        // non-zero before the manual retry resets it.
        val picked = jobs.pickup(jobId, "worker-a", 60_000)!!
        assertTrue(jobs.markFailed(jobId, picked.version, "boom", "stack"))

        val failed = jobs.findById(jobId)!!
        assertEquals(JobState.FAILED, failed.state)
        assertTrue(failed.attempts >= 1)

        val ok = jobs.manualRetry(jobId, failed.version, actor = "alice")
        assertTrue(ok)

        val retried = jobs.findById(jobId)!!
        assertEquals(JobState.ENQUEUED, retried.state)
        assertEquals(0, retried.attempts)
        assertNull(retried.lockedBy)
        assertNull(retried.lockedUntil)
        assertEquals(failed.version + 1, retried.version)

        val event = jobEvents.findByJobId(jobId).single { it.eventType == "MANUAL_RETRY" }
        assertEquals("alice", event.actor)
        assertEquals(JobState.FAILED, event.prevState)
        assertEquals(JobState.ENQUEUED, event.newState)
    }

    @Test
    fun `manualRetry with stale version returns false`() = runBlocking {
        val jobId = scheduler.enqueue(Noop(21))
        val picked = jobs.pickup(jobId, "worker-a", 60_000)!!
        assertTrue(jobs.markFailed(jobId, picked.version, "boom", null))
        val failed = jobs.findById(jobId)!!

        // First retry succeeds, bumps version.
        assertTrue(jobs.manualRetry(jobId, failed.version, "alice"))
        // Second call with the stale version: same state guard would pass (ENQUEUED is
        // not FAILED, so it fails on state too) — but the point is version-CAS rejects it.
        val again = jobs.manualRetry(jobId, failed.version, "bob")
        assertFalse(again)
    }

    @Test
    fun `manualRetry on non-FAILED state returns false`() = runBlocking {
        val jobId = scheduler.enqueue(Noop(22))
        // ENQUEUED is not FAILED — the state guard alone must reject.
        val v = jobs.findById(jobId)!!.version
        assertFalse(jobs.manualRetry(jobId, v, "alice"))
        assertEquals(JobState.ENQUEUED, jobs.findById(jobId)!!.state)
    }

    @Test
    fun `manualRetry ONCE parks attempts one below maxAttempts and records MANUAL_RETRY_ONCE`() = runBlocking {
        val jobId = scheduler.enqueue(Noop(23))   // default max_attempts = 3

        val picked = jobs.pickup(jobId, "worker-a", 60_000)!!
        assertTrue(jobs.markFailed(jobId, picked.version, "boom", "stack"))
        val failed = jobs.findById(jobId)!!
        assertEquals(JobState.FAILED, failed.state)

        val ok = jobs.manualRetry(jobId, failed.version, actor = "alice", mode = RetryMode.ONCE)
        assertTrue(ok)

        val retried = jobs.findById(jobId)!!
        assertEquals(JobState.ENQUEUED, retried.state)
        // ONCE grants exactly one more run: attempts parked one below the ceiling, so the
        // next pickup bumps it to max_attempts and the worker's `attempts < maxAttempts`
        // gate fails the instant this run errors — straight back to FAILED, no auto-retry.
        // Contrast the FRESH_BUDGET test above, which asserts attempts == 0.
        assertEquals(retried.maxAttempts - 1, retried.attempts)
        assertNull(retried.lockedBy)
        assertNull(retried.lockedUntil)
        assertEquals(failed.version + 1, retried.version)

        // Distinct audit event so the timeline tells a one-shot retry apart from a
        // fresh-budget MANUAL_RETRY.
        val event = jobEvents.findByJobId(jobId).single { it.eventType == "MANUAL_RETRY_ONCE" }
        assertEquals("alice", event.actor)
        assertEquals(JobState.FAILED, event.prevState)
        assertEquals(JobState.ENQUEUED, event.newState)
    }

    // --- releaseProcessingLock ----------------------------------------------------------

    @Test
    fun `releaseProcessingLock on PROCESSING returns row to ENQUEUED without bumping attempts`() = runBlocking {
        val jobId = scheduler.enqueue(Noop(30))
        val picked = jobs.pickup(jobId, "worker-a", 60_000)!!
        assertEquals(1, picked.attempts)

        val released = jobs.releaseProcessingLock(jobId, picked.version)
        assertTrue(released)

        val after = jobs.findById(jobId)!!
        assertEquals(JobState.ENQUEUED, after.state)
        assertNull(after.lockedBy)
        assertNull(after.lockedUntil)
        // Attempts NOT reset — that's the whole point of release-vs-retry: the pickup
        // already counted, releasing just returns the row to the queue.
        assertEquals(1, after.attempts)
        assertEquals(picked.version + 1, after.version)
    }

    @Test
    fun `releaseProcessingLock with stale version returns false`() = runBlocking {
        val jobId = scheduler.enqueue(Noop(31))
        val picked = jobs.pickup(jobId, "worker-a", 60_000)!!

        // Heartbeat-equivalent UPDATE bumps version under us via another CAS (markSucceeded
        // would also do, but that flips state — use transitionState to stay on PROCESSING).
        // Simulate by re-pickup-style: directly markFailed which bumps version + flips state.
        assertTrue(jobs.markFailed(jobId, picked.version, null, null))

        val staleRelease = jobs.releaseProcessingLock(jobId, picked.version)
        assertFalse(staleRelease)
        assertEquals(JobState.FAILED, jobs.findById(jobId)!!.state)
    }

    @Test
    fun `releaseProcessingLock on non-PROCESSING row returns false`() = runBlocking {
        val jobId = scheduler.enqueue(Noop(32))
        // ENQUEUED row — releasePROCESSINGlock must not apply.
        val v = jobs.findById(jobId)!!.version
        assertFalse(jobs.releaseProcessingLock(jobId, v))
        assertEquals(JobState.ENQUEUED, jobs.findById(jobId)!!.state)
    }

    // --- markSucceeded version conflict -------------------------------------------------

    @Test
    fun `markSucceeded with stale version returns false`() = runBlocking {
        val jobId = scheduler.enqueue(Noop(40))
        val picked = jobs.pickup(jobId, "worker-a", 60_000)!!

        // Concurrent failure path wins the row first — succeed must then lose.
        assertTrue(jobs.markFailed(jobId, picked.version, "boom", null))
        val lost = jobs.markSucceeded(jobId, picked.version)
        assertFalse(lost)

        assertEquals(JobState.FAILED, jobs.findById(jobId)!!.state)
    }

    @Test
    fun `markSucceeded with current version succeeds and is idempotent on second stale call`() = runBlocking {
        val jobId = scheduler.enqueue(Noop(41))
        val picked = jobs.pickup(jobId, "worker-a", 60_000)!!

        assertTrue(jobs.markSucceeded(jobId, picked.version))
        assertEquals(JobState.SUCCEEDED, jobs.findById(jobId)!!.state)

        // Second call with the now-stale picked.version must be rejected — CAS is the
        // single source of truth, terminal state doesn't get an extra short-circuit.
        assertFalse(jobs.markSucceeded(jobId, picked.version))
    }
}
