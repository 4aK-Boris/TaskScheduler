@file:OptIn(org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi::class)

package cs.trade.scheduler.compiler

import org.jetbrains.kotlin.backend.common.extensions.IrGenerationExtension
import org.jetbrains.kotlin.compiler.plugin.CompilerPluginRegistrar
import org.jetbrains.kotlin.config.CompilerConfiguration

/**
 * Entry point for the kotlinc-side of the TaskScheduler function-ref lambda capture
 * plugin (DESIGN.md 21.9). Registered via `META-INF/services` so kotlinc picks it up
 * when the plugin jar is on the classpath.
 *
 * For now this only wires the [IrGenerationExtension]. A FIR-side checker can be added
 * later if we want compile-time diagnostics for lambdas that the plugin can't rewrite
 * (e.g. multi-statement bodies, calls on non-injectable receivers); without it the
 * runtime stub in `Scheduler.enqueueLambda` carries the error.
 */
public class SchedulerCompilerPluginRegistrar : CompilerPluginRegistrar() {

    override val supportsK2: Boolean = true

    /**
     * Stable id used by kotlinc to look up plugin options (none yet). Must match the
     * `compilerPluginId` returned by the Gradle subplugin in
     * `:scheduler-compiler-plugin-gradle`.
     */
    override val pluginId: String = "cs.trade.scheduler.compiler"

    override fun ExtensionStorage.registerExtensions(configuration: CompilerConfiguration) {
        // Pass the configuration to the IR extension so it can reach the
        // MessageCollector — Stage 1 reports candidate call-sites via the LOGGING
        // channel (visible with `-Xverbose-logs` or via test harnesses); Stage 2 will
        // upgrade those to ERROR for unsupported lambda shapes.
        IrGenerationExtension.registerExtension(SchedulerIrGenerationExtension(configuration))
    }
}
