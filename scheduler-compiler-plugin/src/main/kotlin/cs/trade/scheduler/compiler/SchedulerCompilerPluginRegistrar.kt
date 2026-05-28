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
 * Wires the [IrGenerationExtension] that performs the actual `enqueueLambda { … }` →
 * `enqueueFunctionRefRaw(…)` rewrite. Unsupported lambda shapes are reported as compile
 * ERRORs from the IR stage (see [SchedulerIrGenerationExtension]); a future FIR-side
 * checker could surface those diagnostics earlier (during analysis) but isn't required
 * for correctness — the IR transform already fails the build on a bad shape, and an
 * un-rewritten call hits the throwing stub in `Scheduler.enqueueLambda` at runtime.
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
        // Pass the configuration through so the IR extension can reach the MessageCollector
        // for its rewrite-count LOGGING line and its ERROR diagnostics on unsupported shapes.
        IrGenerationExtension.registerExtension(SchedulerIrGenerationExtension(configuration))
    }
}
