package cs.trade.scheduler.engine.infra.infrastructure.leader

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import java.sql.Connection
import java.sql.DriverManager
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * PG advisory-lock based leader election for the `scheduler-infra` process (DESIGN.md
 * 14.3 — Phase 2 distribution).
 *
 * **Why advisory locks:** zero schema (`pg_try_advisory_lock` is built in), session-scoped
 * (auto-released if the leader crashes or its connection dies), atomic across replicas,
 * cheap. No external coordination service needed.
 *
 * **Why a dedicated raw connection:** `pg_try_advisory_lock` holds the lock for the
 * lifetime of the **session**, not the transaction. Borrowing a connection from Hikari
 * and never returning it would silently consume a pool slot forever. A direct
 * `DriverManager` connection bypasses the pool — its lifecycle is owned by this class.
 *
 * **Liveness tick:** every [electionInterval] (default 5s), the leader pings the
 * connection. If the ping fails (PG restarted, network blip, OOM-killed PG client lib)
 * we step down — another replica will acquire on its next tick. Followers also probe
 * on each tick; they're cheap.
 *
 * Usage in `standalone-runner`:
 * ```
 * val leader = LeaderElection(pgUrl, pgUser, pgPass)
 * leader.start(loopsScope)
 *
 * koin.get<OutboxPublisher>().start(loopsScope, isLeader = leader::isCurrentLeader)
 * // ... other gated loops
 *
 * shutdownHook { leader.release() }
 * ```
 */
public class LeaderElection(
    private val jdbcUrl: String,
    private val jdbcUser: String,
    private val jdbcPassword: String,
    private val key: Long = LEADER_LOCK_KEY,
    private val electionInterval: Duration = 5.seconds,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * Best-effort extraction of the PG host out of the JDBC URL for log correlation. We
     * don't authenticate the parser — a malformed URL would have failed sooner in DriverManager
     * — so on parse miss we just log the full URL.
     */
    private val jdbcHost: String = parseJdbcHost(jdbcUrl) ?: jdbcUrl

    // Held while we're leader, null otherwise. Synchronisation: single coroutine ticks
    // this; isCurrentLeader() reads the StateFlow which is concurrent-safe.
    private var leaderConn: Connection? = null

    private val _isLeader = MutableStateFlow(false)
    public val isLeader: StateFlow<Boolean> = _isLeader.asStateFlow()

    /** Hot read for `() -> Boolean` gates passed to loop .start() methods. */
    public fun isCurrentLeader(): Boolean = _isLeader.value

    public fun start(scope: CoroutineScope): Job = scope.launch {
        log.info("LeaderElection starting — key={}, interval={}", key, electionInterval)
        try {
            while (isActive) {
                runCatching { tick() }.onFailure { log.warn("Election tick failed — will retry", it) }
                delay(electionInterval.inWholeMilliseconds)
            }
        } finally {
            release()
        }
    }

    private fun tick() {
        val current = leaderConn
        if (current != null) {
            // Heartbeat probe — if the underlying socket died, step down so a healthy
            // replica can grab the lock.
            if (current.isClosed || !pingConnection(current)) {
                log.warn("Leader connection died — stepping down")
                runCatching { current.close() }
                leaderConn = null
                _isLeader.value = false
            }
            return
        }
        // Not leader — try to acquire.
        val conn = runCatching { openLeaderConnection() }.getOrElse {
            log.warn("Could not open leader connection (will retry on next tick)", it)
            return
        }
        val acquired = try {
            conn.prepareStatement("SELECT pg_try_advisory_lock(?)").use { stmt ->
                stmt.setLong(1, key)
                stmt.executeQuery().use { rs -> rs.next() && rs.getBoolean(1) }
            }
        } catch (t: Throwable) {
            log.warn("pg_try_advisory_lock failed", t)
            runCatching { conn.close() }
            return
        }
        if (acquired) {
            leaderConn = conn
            _isLeader.value = true
            log.info("Acquired leadership host={} key={}", jdbcHost, key)
        } else {
            // Another replica holds the lock. Return the connection — we'll open a fresh
            // one next tick. (Keeping it open between attempts would leak FDs.)
            runCatching { conn.close() }
        }
    }

    private fun openLeaderConnection(): Connection =
        DriverManager.getConnection(jdbcUrl, jdbcUser, jdbcPassword).also {
            // Defensive: leave autocommit on (default). Advisory locks don't need a tx
            // and we don't want a stray open tx to hold row locks accidentally.
            it.autoCommit = true
        }

    private fun pingConnection(conn: Connection): Boolean = runCatching {
        conn.prepareStatement("SELECT 1").use { it.executeQuery().use { rs -> rs.next() } }
    }.getOrElse { false }

    /**
     * Release leadership: call `pg_advisory_unlock` then close the connection. Both are
     * best-effort — closing the connection releases the lock unconditionally on the PG
     * side, so a failed unlock during shutdown is harmless.
     */
    public fun release() {
        val conn = leaderConn ?: return
        leaderConn = null
        _isLeader.value = false
        runCatching {
            conn.prepareStatement("SELECT pg_advisory_unlock(?)").use { stmt ->
                stmt.setLong(1, key)
                stmt.executeQuery().close()
            }
        }.onFailure { log.warn("pg_advisory_unlock failed (lock will release on conn close)", it) }
        runCatching { conn.close() }
        log.info("Released leadership host={} key={}", jdbcHost, key)
    }

    public companion object {
        /**
         * Arbitrary int64 used as the advisory-lock key. All replicas of the same
         * scheduler-infra cluster must use this exact value. ASCII for "SCHE" picked
         * for readability in pg_locks output.
         */
        public const val LEADER_LOCK_KEY: Long = 0x53434845L

        /**
         * Extract host:port (or host) from a JDBC URL of the shape
         * `jdbc:postgresql://host:port/db?params`. Returns null on a shape we don't
         * recognise — we always treat that as "stick the full URL in the log" rather
         * than throwing, because logging is never worth crashing a leader-election loop.
         */
        internal fun parseJdbcHost(jdbcUrl: String): String? {
            val withoutPrefix = jdbcUrl.removePrefix("jdbc:postgresql://").takeIf { it != jdbcUrl }
                ?: return null
            val hostPort = withoutPrefix.substringBefore('/').substringBefore('?')
            return hostPort.takeIf { it.isNotBlank() }
        }
    }
}
