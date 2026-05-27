// Convention for Compose Multiplatform modules targeting wasmJs (Web).
// Used by :core:frontend (library) and :dashboard-web (application).
// Modules add their own dependencies in their build.gradle.kts.
package buildsrc.convention

import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl

plugins {
    kotlin("multiplatform")
    id("org.jetbrains.compose")
    id("org.jetbrains.kotlin.plugin.compose")
}

kotlin {
    jvmToolchain(21)

    @OptIn(ExperimentalWasmDsl::class)
    wasmJs {
        browser()
    }
}
