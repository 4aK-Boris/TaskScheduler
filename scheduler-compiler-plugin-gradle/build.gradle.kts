// Gradle subplugin wrapper for the kotlinc plugin in :scheduler-compiler-plugin.
//
// User apps apply `id("cs.trade.scheduler.compiler") version "..."` (or via direct
// project dependency in this repo's :app module) and this implementation:
//  - hooks into KotlinCompilerPluginSupportPlugin
//  - points kotlinc at the plugin jar from :scheduler-compiler-plugin
//
// The artifactId / groupId of the kotlinc plugin must match what `getPluginArtifact`
// returns, otherwise Gradle won't find the jar on the classpath.

plugins {
    id("buildsrc.convention.kotlin-jvm")
    `java-gradle-plugin`
}

dependencies {
    compileOnly(libs.kotlinGradlePluginApi)
}

gradlePlugin {
    plugins {
        create("schedulerCompilerPlugin") {
            id = "cs.trade.scheduler.compiler"
            implementationClass = "cs.trade.scheduler.compiler.gradle.SchedulerCompilerGradlePlugin"
            displayName = "TaskScheduler function-ref lambda capture plugin"
            description = "Rewrites `scheduler.enqueueLambda { mailer.send(123) }` into a function-ref enqueue call at compile time. See DESIGN.md 21.9."
        }
    }
}
