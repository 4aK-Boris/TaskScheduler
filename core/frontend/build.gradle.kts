plugins {
    id("buildsrc.convention.compose-multiplatform")
    alias(libs.plugins.kotlinPluginSerialization)
}

kotlin {
    sourceSets {
        wasmJsMain.dependencies {
            api(project(":core:shared"))

            api(compose.runtime)
            api(compose.foundation)
            api(compose.material3)
            api(compose.ui)
            api(compose.components.resources)

            api(libs.bundles.ktorClientWasm)
            api(libs.bundles.decompose)
            api(libs.bundles.kotlinxEcosystem)
        }
    }
}
