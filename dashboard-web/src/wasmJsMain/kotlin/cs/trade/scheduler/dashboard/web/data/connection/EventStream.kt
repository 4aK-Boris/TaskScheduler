package cs.trade.scheduler.dashboard.web.data.connection

import cs.trade.scheduler.core.frontend.api.ApiClient
import cs.trade.scheduler.shared.events.WebSocketEvent
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.browser.window
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
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
 * the same hot [events] flow without each opening their own socket.
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
        val scheme = if (window.location.protocol == "https:") "wss" else "ws"
        val url = "$scheme://${window.location.host}$endpoint"
        ApiClient.http.webSocket(urlString = url) {
            for (frame in incoming) {
                if (frame !is Frame.Text) continue
                val event = runCatching {
                    ApiClient.json.decodeFromString<WebSocketEvent>(frame.readText())
                }.getOrNull() ?: continue
                // First frame is the proof we're really live (not a half-open socket
                // that will time out in 60s). Flip to CONNECTED here, not before the
                // webSocket {} call — a 401 returns from there before any frame and
                // we don't want a flicker through CONNECTED on a failed auth.
                statusStore.set(ConnectionStatus.CONNECTED)
                _events.emit(event)
            }
        }
    }

    private companion object {
        val INITIAL_BACKOFF: Duration = 1.seconds
        val BACKOFF_CAP: Duration = 30.seconds
    }
}
