package cs.trade.scheduler.dashboard.web.data.connection

import com.arkivanov.decompose.value.MutableValue
import com.arkivanov.decompose.value.Value

/**
 * Live state of the dashboard WebSocket. The UI uses this to render a "Live" /
 * "Reconnecting..." badge in the top nav so the operator knows whether they're
 * looking at a stale screen.
 */
public enum class ConnectionStatus {
    // Initial state before EventStream.start, and the brief gap between disconnects
    // and the first reconnect attempt. Treat as "no signal".
    DISCONNECTED,
    // The WS upgrade is in progress, or we're between failed attempts and waiting on
    // the backoff timer. The badge reads "Reconnecting...".
    RECONNECTING,
    // The WS is open and we've received at least one frame (so we know the route is
    // really working, not just half-open).
    CONNECTED,
}

/**
 * Holds the current [ConnectionStatus]. Single writer ([EventStream]); many readers
 * (the RootContent badge today; potentially other screens later).
 *
 * Exposes Decompose's [Value] so Compose can observe it with `subscribeAsState()` —
 * same pattern the screen Models use, no second observation primitive to learn.
 */
public class ConnectionStatusStore {
    private val _state = MutableValue(ConnectionStatus.DISCONNECTED)
    public val state: Value<ConnectionStatus> = _state

    public fun set(status: ConnectionStatus) {
        _state.value = status
    }
}
