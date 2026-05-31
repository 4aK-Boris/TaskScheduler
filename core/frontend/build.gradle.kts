plugins {
    id("buildsrc.convention.compose-multiplatform")
    alias(libs.plugins.kotlinPluginSerialization)
}

kotlin {
    sourceSets {
        wasmJsMain.dependencies {
            api(project(":core:shared"))

            api(libs.composeRuntime)
            api(libs.composeFoundation)
            api(libs.composeMaterial3)
            api(libs.composeUi)
            api(libs.composeComponentsResources)

            api(libs.bundles.ktorClientWasm)
            api(libs.bundles.decompose)
            api(libs.bundles.kotlinxEcosystem)
        }
    }
}

// Bundle the IBM Plex font files (src/commonMain/composeResources/font) and generate the typed
// `Res` accessor used by SchedulerTheme. Explicit package — this module has no `group` set, so
// the default-derived package would be unstable.
compose.resources {
    publicResClass = false
    packageOfResClass = "cs.trade.scheduler.core.frontend.generated.resources"
    generateResClass = always
}
