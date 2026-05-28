@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package cs.trade.scheduler.storage.postgres.infrastructure.events

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.postgresql.PGConnection
import org.slf4j.LoggerFactory
import java.sql.DriverManager
import kotlin.uuid.Uuid

/**
 * Worker-side listener for the `job_cancel` Postgres channel (DESIGN.md 22.7). When a
 * dashboard `cancel` lands on a row that's already PROCESSING, `requestCancellation`
 * stamps `cancel_requested_at` and fires `NOTIFY job_cancel, '<jobId>'`. This listener —
 * running in each worker process — picks that up and hands the jobId to [onSignal] so the
 * [WorkerPool] can cancel the running handler coroutine directly, rather than waiting for
 * the handler to poll the DB.
 *
 * **Why a dedicated raw connection** (not the Hikari pool): `getNotifications` needs a
 * session held in LISTEN mode for the lifetime of the worker; that would tie up a pool
 * slot and Hikari would eventually reap it. Same pattern as [PostgresEventBus] and
 * `LeaderElection`.
 *
 * **Why a separate channel from [PostgresEventBus]**: the default `EventBus` binding is
 * in-memory and per-process — a worker can't rely on the cross-process WS bus being wired.
 * `job_cancel` is a focused, self-contained signal owned entirely by the worker side, so
 * cancel-in-flight works regardless of how the dashboard event bus happens to be set up.
 *
 * Best-effort delivery: a NOTIFY that arrives while the listener is reconnecting is lost,
 * but the stamped `cancel_requested_at` is durable — a cooperative handler still sees it
 * via `JobContext.isCancellationRequested`, and orphan recovery picks up the row on the
 * next pickup. The push path is a latency optimisation, not the correctness guarantee.
 */
public class JobCancelListener(
    private val jdbcUrl: String,
    private val jdbcUser: String,
    private val jdbcPassword: String,
    private val channel: String = DEFAULT_CHANNEL,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * Launch the LISTEN loop on [scope]. [onSignal] is invoked once per received jobId —
     * keep it non-blocking (the [WorkerPool] launches the grace/force-kill work in its own
     * coroutine). Returns the listener [Job] so callers can join/cancel if needed; cancelling
     * [scope] also unwinds the loop within one poll cycle.
     */
    public fun start(scope: CoroutineScope, onSignal: (Uuid) -> Unit): Job =
        scope.launch(Dispatchers.IO) { runListener(onSignal) }

    private suspend fun runListener(onSignal: (Uuid) -> Unit) {
        while (currentCoroutineContext().isActive) {
            try {
                listenLoop(onSignal)
            } catch (t: Throwable) {
                if (currentCoroutineContext().isActive) {
                    log.warn("job_cancel listener died — reconnecting in {}ms", RECONNECT_DELAY_MS, t)
                    delay(RECONNECT_DELAY_MS)
                }
            }
        }
    }

    private fun listenLoop(onSignal: (Uuid) -> Unit) {
        DriverManager.getConnection(jdbcUrl, jdbcUser, jdbcPassword).use { conn ->
            conn.autoCommit = true
            conn.createStatement().use { it.execute("LISTEN \"$channel\"") }
            val pg = conn.unwrap(PGConnection::class.java)
            while (Thread.currentThread().run { !isInterrupted }) {
                // Short blocking poll — responsive to scope cancellation (loop re-checks
                // isInterrupted), low CPU when idle. Null when no notifications arrived.
                val notifications = pg.getNotifications(LISTEN_POLL_MS) ?: continue
                for (notif in notifications) {
                    val jobId = runCatching { Uuid.parse(notif.parameter) }.getOrElse {
                        log.warn("job_cancel NOTIFY carried a non-UUID payload '{}' — ignoring", notif.parameter)
                        continue
                    }
                    runCatching { onSignal(jobId) }
                        .onFailure { log.warn("job_cancel onSignal handler threw for {}", jobId, it) }
                }
            }
        }
    }

    public companion object {
        public const val DEFAULT_CHANNEL: String = "job_cancel"

        /** Listener poll timeout in millis. Short = responsive to cancellation. */
        public const val LISTEN_POLL_MS: Int = 1_000
        public const val RECONNECT_DELAY_MS: Long = 5_000
    }
}
