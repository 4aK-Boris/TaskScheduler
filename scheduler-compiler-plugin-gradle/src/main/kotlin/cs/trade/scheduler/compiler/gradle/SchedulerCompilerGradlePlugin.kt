package cs.trade.scheduler.compiler.gradle

import org.gradle.api.Project
import org.gradle.api.provider.Provider
import org.jetbrains.kotlin.gradle.plugin.KotlinCompilation
import org.jetbrains.kotlin.gradle.plugin.KotlinCompilerPluginSupportPlugin
import org.jetbrains.kotlin.gradle.plugin.SubpluginArtifact
import org.jetbrains.kotlin.gradle.plugin.SubpluginOption

/**
 * Gradle subplugin that registers `:scheduler-compiler-plugin` on every Kotlin
 * compilation in the consuming project (DESIGN.md 21.9). Users apply via:
 *
 * ```
 * plugins {
 *     id("cs.trade.scheduler.compiler") version "…"
 * }
 * ```
 *
 * The plugin doesn't take any options yet — Stage 2 may add diagnostics levels or
 * opt-out for specific source sets, but for the MVP it's just "transform every
 * `enqueueLambda` it sees".
 */
public class SchedulerCompilerGradlePlugin : KotlinCompilerPluginSupportPlugin {

    override fun apply(target: Project): Unit = Unit

    override fun isApplicable(kotlinCompilation: KotlinCompilation<*>): Boolean = true

    override fun getCompilerPluginId(): String = COMPILER_PLUGIN_ID

    override fun getPluginArtifact(): SubpluginArtifact = SubpluginArtifact(
        groupId = ARTIFACT_GROUP_ID,
        artifactId = ARTIFACT_ID,
        version = PLUGIN_VERSION,
    )

    override fun applyToCompilation(kotlinCompilation: KotlinCompilation<*>): Provider<List<SubpluginOption>> {
        // No options yet — return an empty list as a Provider so we don't eagerly
        // realise it during configuration.
        return kotlinCompilation.target.project.provider { emptyList() }
    }

    private companion object {
        // Must match the `cs.trade.scheduler.compiler.SchedulerCompilerPluginRegistrar`
        // package + ID convention. Used by kotlinc to look up the plugin's options.
        const val COMPILER_PLUGIN_ID: String = "cs.trade.scheduler.compiler"
        const val ARTIFACT_GROUP_ID: String = "cs.trade.scheduler"
        const val ARTIFACT_ID: String = "scheduler-compiler-plugin"
        // TODO: tie to project version once we wire the publishing block. For
        // intra-build use, the project-substitution rule in settings.gradle.kts handles
        // the resolution; this version is only consulted in published-coordinate mode.
        const val PLUGIN_VERSION: String = "0.1.0-SNAPSHOT"
    }
}
