plugins {
    id("buildsrc.convention.kotlin-js-react")
    alias(libs.plugins.kotlinPluginSerialization)
}

kotlin {
    sourceSets {
        jsMain.dependencies {
            api(project(":core:shared"))

            // The BOM pins React / Emotion / browser-wrapper versions; the bundle entries are
            // version-less on purpose (see gradle/libs.versions.toml).
            api(project.dependencies.platform(libs.kotlinWrappersBom))
            api(libs.bundles.react)

            api(libs.bundles.ktorClientJs)
            api(libs.bundles.decompose)
            api(libs.bundles.kotlinxEcosystem)
        }
    }
}
