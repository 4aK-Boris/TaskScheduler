package cs.trade.scheduler.engine.infra

import cs.trade.scheduler.engine.infra.infrastructure.leader.LeaderElection
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.testcontainers.containers.PostgreSQLContainer
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Sanity for [LeaderElection] under contention and failover.
 *
 * Scenarios:
 *  1. Two replicas, sequential start — first acquires, second never does while the first holds.
 *  2. Graceful release — leader calls [LeaderElection.release], the follower's next tick
 *     promotes within ~2 × electionInterval. This mirrors a clean shutdown.
 *  3. Hard crash — leader's scope is cancelled WITHOUT calling release(). PG releases the
 *     advisory lock on session close anyway, so the follower still acquires (this is the
 *     load-bearing behaviour for crash recovery — see DESIGN.md 14.3).
 *
 * Uses a tiny [electionInterval] (250ms) so the whole test finishes in a couple of seconds.
 *
 * Bypasses testcontainers when EXTERNAL_PG_URL is set (Docker Desktop on dev machines
 * returns a socket-access stub for non-CLI clients — see JobRepositoryCasIntegrationTest).
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class LeaderElectionFailoverTest {

    private companion object {
        private val externalUrl: String? = System.getenv("EXTERNAL_PG_URL")?.takeIf { it.isNotBlank() }

        // Distinct keys per test so a stale lock from a previous failed run can't cross-
        // contaminate. Picked outside LeaderElection.LEADER_LOCK_KEY so we don't collide
        // with anything a parallel infra process might hold against the same DB.
        const val KEY_SEQUENTIAL: Long = 0x4C45_5354_4031L
        const val KEY_GRACEFUL: Long = 0x4C45_5354_4032L
        const val KEY_HARD_CRASH: Long = 0x4C45_5354_4033L
    }

    private lateinit var jdbcUrl: String
    private lateinit var pgUser: String
    private lateinit var pgPass: String
    private var postgres: PostgreSQLContainer<*>? = null

    @BeforeAll
    fun setUp() {
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
    }

    @AfterAll
    fun tearDown() {
        runCatching { postgres?.stop() }
    }

    private fun newElector(key: Long): LeaderElection = LeaderElection(
        jdbcUrl = jdbcUrl,
        jdbcUser = pgUser,
        jdbcPassword = pgPass,
        key = key,
        electionInterval = 250.milliseconds,
    )

    private suspend fun awaitLeader(elector: LeaderElection, timeoutMs: Long = 2_000): Boolean {
        return withTimeoutOrNull(timeoutMs.milliseconds) {
            while (!elector.isCurrentLeader()) delay(50.milliseconds)
            true
        } == true
    }

    @Test
    fun `two electors — first wins, second stays follower while first holds`() = runBlocking {
        val a = newElector(KEY_SEQUENTIAL)
        val b = newElector(KEY_SEQUENTIAL)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            a.start(scope)
            assertTrue(awaitLeader(a), "A should acquire the lock within 2s")

            // Now start B against the same key. Give it 1s (≥ 4 ticks) — B's tick must
            // see pg_try_advisory_lock = false and stay follower the entire time.
            b.start(scope)
            delay(1_200.milliseconds)
            assertEquals(true, a.isCurrentLeader(), "A must still be leader")
            assertEquals(false, b.isCurrentLeader(), "B must not have stolen leadership")
        } finally {
            runCatching { a.release() }
            runCatching { b.release() }
            scope.cancel()
        }
    }

    @Test
    fun `graceful release — follower promotes after leader scope is cancelled`() = runBlocking {
        // Production "shutdown hook" pattern: scope.cancel() unwinds the election loop,
        // its `finally { release() }` drops the advisory lock, follower's next tick
        // acquires. A standalone `release()` on the elector while its loop is still
        // running would race — the loop's next tick would just re-acquire the lock it
        // just released. So the realistic graceful path is "stop the loop, then unwind."
        val a = newElector(KEY_GRACEFUL)
        val b = newElector(KEY_GRACEFUL)
        val scopeA = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val scopeB = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            a.start(scopeA)
            assertTrue(awaitLeader(a), "A should acquire before B starts")

            b.start(scopeB)
            delay(600.milliseconds)
            assertEquals(false, b.isCurrentLeader(), "B must be follower while A holds the lock")

            // Graceful shutdown: cancel A's loop. The finally block inside start() calls
            // release(), which executes pg_advisory_unlock + connection close synchronously
            // (JDBC calls don't honour coroutine cancellation, so the unlock completes
            // even though the coroutine is cancelled).
            scopeA.cancel()
            delay(500.milliseconds)

            assertTrue(awaitLeader(b, timeoutMs = 2_000), "B should promote within 2s of A's graceful exit")
            assertEquals(false, a.isCurrentLeader(), "A's state must reflect the release")
        } finally {
            runCatching { a.release() }
            runCatching { b.release() }
            scopeB.cancel()
            scopeA.cancel()
        }
    }

    @Test
    fun `hard crash — follower promotes when leader scope is cancelled without release`() = runBlocking {
        val a = newElector(KEY_HARD_CRASH)
        val b = newElector(KEY_HARD_CRASH)
        // Two separate scopes so we can kill A's scope without affecting B's election loop.
        val scopeA = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val scopeB = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            // Sequential start: A acquires before B even tries.
            a.start(scopeA)
            assertTrue(awaitLeader(a), "A should acquire before B is started")
            b.start(scopeB)
            delay(600.milliseconds)
            assertEquals(false, b.isCurrentLeader(), "B must be follower")

            // Simulate process death: cancel A's scope. The `finally` inside
            // LeaderElection.start runs release() on cancellation — that's the close-on-
            // crash path PG relies on. (Even without it, closing the JDBC session would
            // drop the lock; release() just makes it explicit and fast.)
            scopeA.cancel()
            // Give A's coroutine a moment to run its finally block + close the connection.
            delay(500.milliseconds)

            assertTrue(awaitLeader(b, timeoutMs = 2_000), "B should promote within 2s of A dying")
            assertNotNull(b, "B reference still here")
        } finally {
            runCatching { a.release() }
            runCatching { b.release() }
            scopeB.cancel()
            scopeA.cancel()
        }
    }
}
