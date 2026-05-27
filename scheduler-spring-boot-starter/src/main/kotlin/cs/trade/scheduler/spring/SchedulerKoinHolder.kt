package cs.trade.scheduler.spring

import org.koin.core.Koin
import org.koin.core.context.stopKoin

/**
 * Thin wrapper around the Koin instance the starter creates. We expose this as a Spring
 * bean (rather than inlining `Koin` directly) so we can hang an `AutoCloseable` off it —
 * Spring then closes the global Koin context on shutdown.
 *
 * **Single-context caveat.** `startKoin {}` populates the JVM-global `GlobalContext`.
 * Running multiple Spring contexts in the same JVM (rare outside tests with
 * `@ContextHierarchy`) will conflict — only one starter at a time may own the Koin
 * context. The `stopKoin()` here is wrapped in `runCatching` so a double-close from
 * test infrastructure doesn't crash the Spring shutdown lifecycle.
 */
public class SchedulerKoinHolder(public val koin: Koin) : AutoCloseable {
    override fun close() {
        runCatching { stopKoin() }
    }
}
