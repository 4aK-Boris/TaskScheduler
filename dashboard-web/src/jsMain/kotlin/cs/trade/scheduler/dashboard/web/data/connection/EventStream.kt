package cs.trade.scheduler.dashboard.web.data.connection

import cs.trade.scheduler.core.frontend.api.ApiClient
import cs.trade.scheduler.shared.events.EventFilter
import cs.trade.scheduler.shared.events.WebSocketEvent
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.browser.window
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.coroutines.coroutineContext
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Single shared WebSocket subscription for the whole web app. Owns the reconnect
 * loop with exponential backoff and feeds [ConnectionStatusStore] so the top-nav
 * badge stays accurate even as the user navigates between screens.
 *
 * Lives in the data layer (not in a Decompose component) because its lifetime is
 * the browser tab, not any one screen — JobList, Recurring, Workers etc. all see
 * the same hot [events] flow without each opening their own socket. That shared
 * socket is intentionally UNFILTERED: JobList alone reacts to nearly every event
 * type, so the union of screen interests is broad.
 *
 * For a narrow interest — JobDetail watching one job's progress — use [subscribe],
 * which opens a SEPARATE, server-side-filtered socket (DESIGN.md 9.2) so the client
 * never receives the cross-job progress flood it would otherwise discard locally.
 *
 * Two failure modes we recover from (same as the per-component loop this replaced):
 *  1. Server restart / network blip — collect throws, we wait `backoff` then retry.
 *  2. Fresh-tab auth race — the SPA opens WS before BasicAuth cached creds, so the
 *     initial upgrade gets 401. The next reconnect succeeds once the user enters
 *     creds for the parallel REST call.
 *
 * `events` is replay-0 / extra-buffer-64 — collectors started after a burst of
 * events will not see history (that's the dashboard's REST refresh-on-reconnect job).
 */
public class EventStream(
    private val statusStore: ConnectionStatusStore,
    private val endpoint: String = "/api/ws/events",
) {
    private val _events = MutableSharedFlow<WebSocketEvent>(extraBufferCapacity = 64)
    public val events: SharedFlow<WebSocketEvent> = _events

    public fun start(scope: CoroutineScope) {
        scope.launch { loop() }
    }

    /**
     * A server-side-filtered event stream. Collecting opens a dedicated WebSocket carrying
     * [filter] as query params (DESIGN.md 9.2); the server forwards only matching events, so
     * the client does no filtering of its own. The socket lives for the collection — cancel
     * the collecting coroutine (e.g. let the owning component's scope close) and it closes.
     *
     * Reconnects with the same backoff as the shared socket, but does NOT touch
     * [ConnectionStatusStore] — the shared socket owns the connection badge; an auxiliary
     * per-screen subscription flapping shouldn't repaint it.
     */
    public fun subscribe(filter: EventFilter): Flow<WebSocketEvent> = flow {
        var backoff = INITIAL_BACKOFF
        while (coroutineContext.isActive) {
            val outcome = runCatching {
                streamFrames(
                    request = {
                        filter.jobIds.forEach { url.parameters.append("jobId", it) }
                        filter.queues.forEach { url.parameters.append("queue", it) }
                        filter.payloadTypes.forEach { url.parameters.append("type", it) }
                        filter.eventTypes.forEach { url.parameters.append("eventType", it) }
                    },
                ) { event -> emit(event) }
            }
            outcome.exceptionOrNull()?.let { if (it is CancellationException) throw it }
            if (!coroutineContext.isActive) break
            delay(backoff)
            backoff = (backoff * 2).coerceAtMost(BACKOFF_CAP)
            if (outcome.isSuccess) backoff = INITIAL_BACKOFF
        }
    }

    private suspend fun loop() {
        var backoff = INITIAL_BACKOFF
        while (coroutineContext.isActive) {
            statusStore.set(ConnectionStatus.RECONNECTING)
            val outcome = runCatching { collectOnce() }
            // Propagate cancellation cleanly so the host scope can shut us down.
            outcome.exceptionOrNull()?.let { if (it is CancellationException) throw it }

            if (!coroutineContext.isActive) break
            statusStore.set(ConnectionStatus.DISCONNECTED)
            delay(backoff)
            backoff = (backoff * 2).coerceAtMost(BACKOFF_CAP)
            // The reset happens on the first frame received in collectOnce — keep the
            // local backoff at its current value across re-entries until that proof.
            if (outcome.isSuccess) backoff = INITIAL_BACKOFF
        }
    }

    private suspend fun collectOnce() {
        // CONNECTED is set the instant the WS upgrade succeeds (see streamFrames) — an idle
        // server sends no frames, so waiting for one would falsely read as "Reconnecting".
        streamFrames(onConnected = { statusStore.set(ConnectionStatus.CONNECTED) }) { event ->
            _events.emit(event)
        }
    }

    /**
     * Open one socket to [endpoint] (optionally extended via [request], e.g. filter query
     * params), decode each text frame as a [WebSocketEvent], and hand it to [onEvent].
     * [onConnected] fires per decoded frame (idempotent — used to flip the connection badge).
     * Returns when the socket closes; the caller owns retry/backoff.
     */
    private suspend fun streamFrames(
        request: HttpRequestBuilder.() -> Unit = {},
        onConnected: () -> Unit = {},
        onEvent: suspend (WebSocketEvent) -> Unit,
    ) {
        val scheme = if (window.location.protocol == "https:") "wss" else "ws"
        val url = "$scheme://${window.location.host}$endpoint"
        ApiClient.http.webSocket(urlString = url, request = request) {
            // Upgrade succeeded → we're genuinely live. Flip to CONNECTED on OPEN, not on the
            // first decoded frame: an idle server (no job activity) sends no frames for minutes,
            // and gating on the first frame left the badge stuck on "Reconnecting" over a perfectly
            // healthy socket (observed in prod). A failed upgrade (e.g. 401) throws before this
            // block ever runs, so there's no CONNECTED flicker on auth failure.
            onConnected()
            for (frame in incoming) {
                if (frame !is Frame.Text) continue
                val event = runCatching {
                    ApiClient.json.decodeFromString<WebSocketEvent>(frame.readText())
                }.getOrNull() ?: continue
                onEvent(event)
            }
        }
    }

    private companion object {
        val INITIAL_BACKOFF: Duration = 1.seconds
        val BACKOFF_CAP: Duration = 30.seconds
    }
}
