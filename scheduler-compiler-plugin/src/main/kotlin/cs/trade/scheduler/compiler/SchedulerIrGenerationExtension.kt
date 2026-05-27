package cs.trade.scheduler.compiler

import org.jetbrains.kotlin.backend.common.extensions.IrGenerationExtension
import org.jetbrains.kotlin.backend.common.extensions.IrPluginContext
import org.jetbrains.kotlin.cli.common.messages.CompilerMessageSeverity
import org.jetbrains.kotlin.cli.common.messages.MessageCollector
import org.jetbrains.kotlin.config.CommonConfigurationKeys
import org.jetbrains.kotlin.config.CompilerConfiguration
import org.jetbrains.kotlin.ir.declarations.IrModuleFragment
import org.jetbrains.kotlin.ir.expressions.IrCall
import org.jetbrains.kotlin.ir.util.kotlinFqName
import org.jetbrains.kotlin.ir.visitors.IrVisitorVoid
import org.jetbrains.kotlin.ir.visitors.acceptChildrenVoid

/**
 * IR-stage transformer that finds `Scheduler.enqueueLambda { … }` call sites and
 * (eventually) rewrites them into the runtime `Scheduler.enqueueFunctionRef(method,
 * args, opts)` form.
 *
 * **Stage 1 (current):** the visitor walks the module's IR, identifies candidate calls
 * by symbol FQN match (`cs.trade.scheduler.core.backend.Scheduler.enqueueLambda`), and
 * reports them via the compiler's MessageCollector. No transformation yet — this is the
 * "wiring is alive" checkpoint.
 *
 * **Stage 2:** parse the lambda argument's body (must be a single IrCall on a captured
 * receiver), extract the method reference + args, replace the parent IrCall with one
 * targeting `enqueueFunctionRef`.
 *
 * **Why FQN-string matching instead of symbol comparison?** The plugin module can't link
 * against `:core:backend` to grab the `Scheduler.enqueueLambda` symbol directly without
 * pulling the entire scheduler runtime into kotlinc's classpath. String-matching is
 * portable and the FQN of a stable public API is a contract — if we ever rename the
 * function, both the consumer and the plugin update in lockstep.
 */
public class SchedulerIrGenerationExtension(
    private val configuration: CompilerConfiguration? = null,
) : IrGenerationExtension {

    private fun messageCollector(): MessageCollector? =
        configuration?.get(CommonConfigurationKeys.MESSAGE_COLLECTOR_KEY)

    override fun generate(moduleFragment: IrModuleFragment, pluginContext: IrPluginContext) {
        val collector = messageCollector()
        val visitor = EnqueueLambdaFinder(collector)
        moduleFragment.acceptChildrenVoid(visitor)
        if (visitor.found > 0) {
            collector?.report(
                CompilerMessageSeverity.LOGGING,
                "scheduler-compiler-plugin: found ${visitor.found} enqueueLambda call-site(s) " +
                    "in ${moduleFragment.name} (Stage 1 — no transformation applied yet)",
            )
        }
    }

    /**
     * Visitor that counts and logs candidate `enqueueLambda` calls. Replaced by an
     * `IrElementTransformerVoid` in Stage 2 once we actually rewrite the IR.
     */
    private class EnqueueLambdaFinder(
        private val collector: MessageCollector?,
    ) : IrVisitorVoid() {

        var found: Int = 0

        override fun visitElement(element: org.jetbrains.kotlin.ir.IrElement) {
            element.acceptChildrenVoid(this)
        }

        override fun visitCall(expression: IrCall) {
            val owner = runCatching { expression.symbol.owner }.getOrNull()
            val fqn = owner?.kotlinFqName?.asString()
            if (fqn == ENQUEUE_LAMBDA_FQN) {
                found++
                collector?.report(
                    CompilerMessageSeverity.LOGGING,
                    "scheduler-compiler-plugin: enqueueLambda call at ${expression.startOffset}..${expression.endOffset}",
                )
            }
            super.visitCall(expression)
        }
    }

    private companion object {
        const val ENQUEUE_LAMBDA_FQN: String = "cs.trade.scheduler.core.backend.Scheduler.enqueueLambda"
    }
}
