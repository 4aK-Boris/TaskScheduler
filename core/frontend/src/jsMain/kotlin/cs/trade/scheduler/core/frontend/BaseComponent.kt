package cs.trade.scheduler.core.frontend

import com.arkivanov.decompose.ComponentContext
import com.arkivanov.essenty.lifecycle.coroutines.coroutineScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * Base for all Decompose components in the dashboard. Provides a [CoroutineScope] tied to
 * the component lifecycle (cancelled on destroy), and acts as a delegated [ComponentContext]
 * so subclasses can call `childStack(...)` directly without `ctx.` prefixes.
 *
 * Convention: every `Default{Name}Component` extends [BaseComponent], the `{Name}Component`
 * interface stays free of inheritance (mirrors main project's "interface stays clean" rule).
 */
public abstract class BaseComponent(
    componentContext: ComponentContext,
) : ComponentContext by componentContext {

    /** Cancelled automatically when the component is destroyed. */
    protected val scope: CoroutineScope =
        coroutineScope(Dispatchers.Main.immediate + SupervisorJob())
}
