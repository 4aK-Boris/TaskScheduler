package cs.trade.scheduler.core.frontend.api

import cs.trade.scheduler.shared.events.WebSocketEvent
import io.ktor.client.HttpClient
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json

/**
 * Subscribes once to `/api/events` and re-broadcasts decoded [WebSocketEvent]s as a
 * [SharedFlow]. Reconnect-on-failure is wired in MVP+1 — for now a single connection;
 * if it dies, the user has to refresh.
 *
 * Components consume via `subscriber.events.collect { ... }` inside their lifecycle scope.
 */
public class WebSocketSubscriber(
    private val http: HttpClient = ApiClient.http,
    private val json: Json = ApiClient.json,
    private val endpoint: String = "/api/events",
) {

    private val _events = MutableSharedFlow<WebSocketEvent>(extraBufferCapacity = 64)
    public val events: SharedFlow<WebSocketEvent> = _events

    public fun start(scope: CoroutineScope): Job = scope.launch {
        http.webSocket(endpoint) {
            for (frame in incoming) {
                if (frame is Frame.Text) {
                    val text = frame.readText()
                    runCatching { json.decodeFromString<WebSocketEvent>(text) }
                        .onSuccess { _events.emit(it) }
                }
            }
        }
    }
}
