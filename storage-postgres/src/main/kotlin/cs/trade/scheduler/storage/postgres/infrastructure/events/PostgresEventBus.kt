package cs.trade.scheduler.storage.postgres.infrastructure.events

import cs.trade.scheduler.core.backend.events.EventBus
import cs.trade.scheduler.shared.events.WebSocketEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.postgresql.PGConnection
import org.slf4j.LoggerFactory
import java.sql.DriverManager

/**
 * Cross-process [EventBus] backed by Postgres LISTEN/NOTIFY (DESIGN.md 14.3 — multi-replica
 * dashboard support). Same-JVM subscribers see local events synchronously; cross-replica
 * subscribers see them after one PG roundtrip.
 *
 * **Why LISTEN/NOTIFY** (vs Rabbit fanout / Redis pub-sub): we already have a Postgres
 * dependency; no new infra to operate. Payload limit (~8 KB) is plenty for compact
 * [WebSocketEvent] envelopes. Trade-off: PG NOTIFY is best-effort transactional, so a
 * NOTIFY during an aborted transaction won't be sent — fine for our "transient signal"
 * semantics (dashboard refreshes anyway on reconnect via REST).
 *
 * **Why a dedicated listener connection**: `PGConnection.getNotifications` requires a
 * session in LISTEN mode, blocks the connection long-term, and shouldn't compete with
 * Hikari's pool. Same pattern as `LeaderElection` — bypass the pool via DriverManager.
 *
 * **Why `Envelope(origin, event)`**: the `NOTIFY` broadcast goes to ALL listeners
 * including this node. We publish locally via [publish] *and* via PG so other replicas
 * see it — when our listener picks up our own message, we'd emit it twice. The
 * `origin == nodeId` check filters out the echo.
 */
public class PostgresEventBus(
    private val jdbcUrl: String,
    private val jdbcUser: String,
    private val jdbcPassword: String,
    private val nodeId: String,
    private val json: Json,
    private val channel: String = DEFAULT_CHANNEL,
) : EventBus {

    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * Outgoing queue: [publish] writes here (non-blocking), [runPublisher] drains and
     * does the NOTIFY. Decouples synchronous publish() callers from DB I/O.
     */
    private val outgoing = MutableSharedFlow<WebSocketEvent>(
        replay = 0,
        extraBufferCapacity = OUTGOING_BUFFER,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    private val _events = MutableSharedFlow<WebSocketEvent>(
        replay = 0,
        extraBufferCapacity = EVENT_BUFFER,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    override val events: SharedFlow<WebSocketEvent> = _events.asSharedFlow()

    override fun publish(event: WebSocketEvent) {
        // Same-replica subscribers get the event with zero PG latency.
        _events.tryEmit(event)
        // Queue for cross-replica broadcast — the publisher coroutine drains this.
        outgoing.tryEmit(event)
    }

    public fun start(scope: CoroutineScope): Job = scope.launch {
        launch(Dispatchers.IO) { runListener() }
        runPublisher()  // collect; suspends on this coroutine
    }

    private suspend fun runListener() {
        while (currentCoroutineContext().isActive) {
            try {
                listenLoop()
            } catch (t: Throwable) {
                if (currentCoroutineContext().isActive) {
                    log.warn("EventBus listener died on node={} — reconnecting in {}ms", nodeId, RECONNECT_DELAY_MS, t)
                    delay(RECONNECT_DELAY_MS)
                }
            }
        }
    }

    private fun listenLoop() {
        DriverManager.getConnection(jdbcUrl, jdbcUser, jdbcPassword).use { conn ->
            conn.autoCommit = true
            conn.createStatement().use { it.execute("LISTEN \"$channel\"") }
            val pg = conn.unwrap(PGConnection::class.java)
            while (Thread.currentThread().run { !isInterrupted }) {
                // Blocking poll with timeout — short enough to react to cancellation, long
                // enough to avoid CPU burn. Returns null when no notifications arrived.
                val notifications = pg.getNotifications(LISTEN_POLL_MS) ?: continue
                for (notif in notifications) {
                    val envelope = runCatching {
                        json.decodeFromString(Envelope.serializer(), notif.parameter)
                    }.getOrElse {
                        log.warn("Could not decode WS envelope from NOTIFY — dropping", it)
                        continue
                    }
                    // Skip echoes of our own publishes (we already emitted them locally).
                    if (envelope.origin == nodeId) continue
                    _events.tryEmit(envelope.event)
                }
            }
        }
    }

    private suspend fun runPublisher() {
        outgoing.collect { event ->
            val envelope = Envelope(origin = nodeId, event = event)
            val payload = runCatching { json.encodeToString(Envelope.serializer(), envelope) }
                .getOrElse {
                    log.warn("Could not encode WS envelope — dropping", it)
                    return@collect
                }
            if (payload.length > NOTIFY_PAYLOAD_LIMIT) {
                log.warn("Event payload too large on node={} ({}B) — dropping", nodeId, payload.length)
                return@collect
            }
            try {
                publishToPg(payload)
            } catch (t: Throwable) {
                log.warn("NOTIFY publish failed on node={} — dropping event", nodeId, t)
            }
        }
    }

    private suspend fun publishToPg(payload: String) {
        withContext(Dispatchers.IO) {
            // Short-lived dedicated connection — same as listener but per-call, since we
            // can't use the listener conn from another thread / coroutine safely. Cost
            // is one TCP handshake per published event; acceptable for dashboard events.
            // Optimisation path: hold a publisher connection too, drain serially.
            DriverManager.getConnection(jdbcUrl, jdbcUser, jdbcPassword).use { conn ->
                conn.autoCommit = true
                conn.prepareStatement("SELECT pg_notify(?, ?)").use { stmt ->
                    stmt.setString(1, channel)
                    stmt.setString(2, payload)
                    stmt.executeQuery().close()
                }
            }
        }
    }

    @Serializable
    private data class Envelope(val origin: String, val event: WebSocketEvent)

    public companion object {
        public const val DEFAULT_CHANNEL: String = "scheduler_events"
        public const val OUTGOING_BUFFER: Int = 256
        public const val EVENT_BUFFER: Int = 256

        /** Listener poll timeout in millis. Short = responsive to cancellation. */
        public const val LISTEN_POLL_MS: Int = 1_000
        public const val RECONNECT_DELAY_MS: Long = 5_000

        /** PG NOTIFY payload limit is 8000 bytes; we leave headroom for length prefix. */
        public const val NOTIFY_PAYLOAD_LIMIT: Int = 7_500
    }
}
