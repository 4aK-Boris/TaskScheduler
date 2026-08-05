package cs.trade.scheduler.core.frontend.react

import com.arkivanov.decompose.value.Value
import react.Cleanup
import react.useMemo
import react.useSyncExternalStore

/**
 * The bridge between Decompose and React: subscribe a component to a [Value] and re-render it
 * whenever the value changes.
 *
 * ```
 * val JobListContent = FC<JobListProps> { props ->
 *     val model = useValue(props.component.model)
 *     ...
 * }
 * ```
 *
 * Built on `useSyncExternalStore` rather than `useState` + `useEffect` because that is React's
 * purpose-built primitive for an external mutable store: it reads the snapshot during render (so
 * the first paint already shows the current value — no one-frame flash of stale state) and it is
 * tear-safe under concurrent rendering.
 *
 * The subscribe callback is memoised on [value] — passing a fresh lambda every render would make
 * React tear down and re-establish the subscription on each pass.
 */
public fun <T : Any> useValue(value: Value<T>): T {
    val subscribe: (() -> Unit) -> Cleanup = useMemo(value) {
        { onStoreChange: () -> Unit ->
            // Decompose emits the current value synchronously on subscription; that first
            // callback is a harmless no-op re-render request React coalesces away.
            val cancellation = value.subscribe { onStoreChange() }
            val cleanup: Cleanup = { cancellation.cancel() }
            cleanup
        }
    }

    return useSyncExternalStore(
        subscribe = subscribe,
        getSnapshot = { value.value },
    )
}
