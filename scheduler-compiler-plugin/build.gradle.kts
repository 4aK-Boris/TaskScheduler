// K2 compiler plugin for Phase B function-ref lambda capture (DESIGN.md 21.9).
//
// This module produces the kotlinc-side plugin JAR. It depends on
// `kotlin-compiler-embeddable` to use FIR / IR APIs at compile time. The dep is
// `compileOnly` because kotlinc supplies the same classes at runtime when the plugin is
// loaded — bundling them would clash with the host compiler's classloader.
//
// The Gradle subplugin lives in :scheduler-compiler-plugin-gradle and references this
// module's coordinates so user-apps' Gradle classpath picks up THIS jar as a kotlinc
// plugin dependency.

plugins {
    id("buildsrc.convention.kotlin-jvm")
}

// Compiler plugin runs against compiler internals — those APIs are not @ExperimentalApi
// annotated in 2.3, so we don't need an @OptIn block here, but if Kotlin minor upgrade
// adds annotations to FirExtensionRegistrar / IrGenerationExtension that's the first
// place to look.

dependencies {
    compileOnly(libs.kotlinCompilerEmbeddable)

    testImplementation(libs.bundles.test)
    testRuntimeOnly(libs.junitPlatformLauncher)
    testImplementation(libs.kotlinCompilerEmbeddable)
}
