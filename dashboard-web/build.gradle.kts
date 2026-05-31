import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl

plugins {
    id("buildsrc.convention.compose-multiplatform")
    alias(libs.plugins.kotlinPluginSerialization)
}

kotlin {
    @OptIn(ExperimentalWasmDsl::class)
    wasmJs {
        binaries.executable()
        browser {
            commonWebpackConfig {
                outputFileName = "dashboard-web.js"
            }
        }
    }

    sourceSets {
        wasmJsMain.dependencies {
            implementation(project(":core:shared"))
            implementation(project(":core:frontend"))

            implementation(libs.composeRuntime)
            implementation(libs.composeFoundation)
            implementation(libs.composeMaterial3)
            implementation(libs.composeUi)

            implementation(libs.bundles.decompose)
            implementation(libs.bundles.ktorClientWasm)
            implementation(libs.bundles.kotlinxEcosystem)
        }
    }
}
