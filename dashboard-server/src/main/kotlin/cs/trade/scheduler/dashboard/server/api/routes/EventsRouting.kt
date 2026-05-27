package cs.trade.scheduler.dashboard.server.api.routes

import cs.trade.scheduler.core.backend.SchedulerCoreConfig
import cs.trade.scheduler.core.backend.events.EventBus
import cs.trade.scheduler.shared.events.WebSocketEvent
import io.ktor.server.auth.UserIdPrincipal
import io.ktor.server.auth.principal
import io.ktor.server.routing.Route
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.Frame
import io.ktor.websocket.send
import kotlinx.serialization.json.Json
import org.koin.ktor.ext.inject
import org.slf4j.LoggerFactory

// /api/ws/events — server-push of WebSocketEvent firehose for the dashboard.
//
// Auth: this route is registered through configureDashboardRouting() which
// :standalone-runner mounts INSIDE `authenticate("dashboard")` when DASHBOARD_AUTH_PASSWORD
// is set. Ktor validates the BasicAuth Authorization header during the HTTP upgrade
// phase, so a connection from a non-credentialled client is rejected with 401 before
// the WS handshake completes.
//
// Browser-side caveat: the JS WebSocket API has no way to set the Authorization header
// manually. We rely on the browser caching BasicAuth credentials per (origin, realm)
// after the first HTTP request that triggered the auth prompt. The SPA bundle is
// served from the same origin and itself requires auth, so by the time JavaScript
// opens the WebSocket the credentials are already in cache and get attached
// automatically to the WS upgrade request.
//
// Non-browser callers (curl, internal tooling) MUST send the `Authorization: Basic ...`
// header on the upgrade request just like any other HTTP call.
//
// Frame format: one JSON object per text frame (no batching), polymorphic via the
// `_type` discriminator (SchedulerCoreConfig.json defaults). Clients pull full state
// via REST when they receive an event — events are invalidation hints, not snapshots.
//
// Backpressure: the underlying SharedFlow uses BufferOverflow.DROP_OLDEST (see
// InMemoryEventBus), so a slow consumer loses events but never blocks the engine.
// Reconnects refetch state via REST.
public fun Route.configureEventsRouting() {
    val eventBus by inject<EventBus>()
    val coreConfig by inject<SchedulerCoreConfig>()
    val json: Json = coreConfig.json
    val log = LoggerFactory.getLogger("cs.trade.scheduler.dashboard.server.events")

    webSocket("/api/ws/events") {
        val actor = call.principal<UserIdPrincipal>()?.name ?: "anonymous"
        val remote = call.request.local.remoteHost
        log.info("WebSocket /api/ws/events connected — actor={}, remote={}", actor, remote)
        try {
            eventBus.events.collect { event ->
                send(Frame.Text(json.encodeToString(WebSocketEvent.serializer(), event)))
            }
        } catch (t: Throwable) {
            log.debug("WebSocket /api/ws/events disconnected (actor={}): {}", actor, t.message)
        }
    }
}
