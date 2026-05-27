package cs.trade.scheduler.core.backend.events

import cs.trade.scheduler.shared.events.WebSocketEvent
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * In-process pub-sub for engine-side events that surface on the dashboard via
 * `/api/ws/events`. Single shared instance per JVM (single-process MVP — standalone-runner
 * has the engine loops and the dashboard server in one process).
 *
 * Multi-replica deployments will need a cross-process transport — Postgres LISTEN/NOTIFY
 * (DESIGN.md 14.3 Phase 2) or a Rabbit fanout — but the call sites here stay the same.
 *
 * Buffer policy: drop-oldest under burst, so a slow WebSocket consumer can't backpressure
 * a hot job-state path. Dashboard clients re-pull state via REST when they reconnect.
 */
public interface EventBus {
    public val events: SharedFlow<WebSocketEvent>

    /** Non-blocking emit. Drops events when the buffer is full (see class KDoc). */
    public fun publish(event: WebSocketEvent)

    /** Sink that does nothing — for tests and modules that don't care about events. */
    public object NoOp : EventBus {
        override val events: SharedFlow<WebSocketEvent> =
            MutableSharedFlow<WebSocketEvent>().asSharedFlow()
        override fun publish(event: WebSocketEvent): Unit = Unit
    }
}

public class InMemoryEventBus : EventBus {

    private val _events = MutableSharedFlow<WebSocketEvent>(
        replay = 0,
        extraBufferCapacity = BUFFER_CAPACITY,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    override val events: SharedFlow<WebSocketEvent> = _events.asSharedFlow()

    override fun publish(event: WebSocketEvent) {
        // tryEmit honours BufferOverflow.DROP_OLDEST — returns true on accept, false only
        // if the flow has no replay AND no subscribers (rare; harmless).
        _events.tryEmit(event)
    }

    private companion object {
        const val BUFFER_CAPACITY = 256
    }
}
