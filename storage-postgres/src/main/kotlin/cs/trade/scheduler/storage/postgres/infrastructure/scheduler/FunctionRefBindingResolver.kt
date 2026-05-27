@file:OptIn(org.koin.core.annotation.KoinInternalApi::class)

package cs.trade.scheduler.storage.postgres.infrastructure.scheduler

import org.koin.core.Koin
import org.koin.core.qualifier.named

/**
 * Verifies that a function-ref enqueue will actually be able to resolve its target at
 * execute time (DESIGN.md 21.5). The check runs on the enqueue side so the developer
 * sees a fail-fast `IllegalArgumentException` at the call site, not a delayed
 * `NoBeanDefFoundException` from a worker minutes later.
 *
 * Default impl ([AlwaysOk]) is used by tests that don't exercise the function-ref path;
 * production wiring in `schedulerPostgresModule` injects [KoinBacked] which reaches into
 * the running Koin context.
 *
 * The check is deliberately narrow:
 *  - **No qualifier given** → exactly one binding for the target class must exist. If
 *    there are 0 → "no Koin binding"; if there are > 1 → "ambiguous, supply a qualifier".
 *  - **Qualifier given** → just probe `get(qualifier = named(...))`. Koin throws if it's
 *    missing; we re-wrap as IAE for consistency with the no-qualifier path.
 *
 * NOTE: We don't validate the method signature here — that's [FunctionRefEnqueuer]'s
 * job (it builds the signature from the KFunction reference and can't fail). The runner
 * is the only place that can hit a "method not found" mismatch, and even then only after
 * a refactor that the design explicitly calls out as a known trade-off (DESIGN.md 21.7).
 */
public fun interface FunctionRefBindingResolver {

    /**
     * @throws IllegalArgumentException if the target type can't be resolved with the
     *   given qualifier in the current Koin context.
     */
    public fun requireResolvable(targetTypeFqn: String, qualifier: String?)

    /** Used by tests and constructors that don't want to wire Koin yet. */
    public object AlwaysOk : FunctionRefBindingResolver {
        override fun requireResolvable(targetTypeFqn: String, qualifier: String?): Unit = Unit
    }

    public class KoinBacked(private val koin: Koin) : FunctionRefBindingResolver {
        override fun requireResolvable(targetTypeFqn: String, qualifier: String?) {
            val kClass = runCatching { Class.forName(targetTypeFqn).kotlin }
                .getOrElse {
                    throw IllegalArgumentException(
                        "Function-ref API: target class $targetTypeFqn is not on the classpath. " +
                            "Did the class get renamed or moved between enqueue and worker?",
                        it,
                    )
                }
            if (qualifier == null) {
                // `Scope.getAll(KClass)` is the public way to enumerate all bindings whose
                // primary/secondary type matches `kClass`. Reflection through Koin's
                // internal `instanceRegistry` works too but is `@KoinInternalAPI`.
                val matches: List<Any> = koin.scopeRegistry.rootScope.getAll(kClass)
                require(matches.isNotEmpty()) {
                    "Function-ref API: no Koin binding for $targetTypeFqn. Register " +
                        "`single { … } bind ${kClass.simpleName}::class` (or its @Single equivalent) " +
                        "before enqueueing a function-ref against this class."
                }
                require(matches.size == 1) {
                    "Function-ref API: ${matches.size} bindings for $targetTypeFqn. " +
                        "Pass EnqueueOptions(targetQualifier = \"...\") to disambiguate, or " +
                        "reference a concrete subclass via Class::method instead."
                }
            } else {
                // Probe — Koin throws on missing-or-mistyped. Re-wrap as IAE so callers can
                // catch on a uniform exception type.
                runCatching {
                    koin.scopeRegistry.rootScope.get<Any>(clazz = kClass, qualifier = named(qualifier), parameters = null)
                }.getOrElse {
                    throw IllegalArgumentException(
                        "Function-ref API: no Koin binding for $targetTypeFqn qualified by " +
                            "\"$qualifier\".",
                        it,
                    )
                }
            }
        }
    }
}
