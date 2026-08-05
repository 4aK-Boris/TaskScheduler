plugins {
    id("buildsrc.convention.kotlin-js-react")
    alias(libs.plugins.kotlinPluginSerialization)
}

kotlin {
    js(IR) {
        binaries.executable()
        browser {
            commonWebpackConfig {
                outputFileName = "dashboard-web.js"
            }
        }
    }

    sourceSets {
        jsMain.dependencies {
            implementation(project(":core:shared"))
            // :core:frontend re-exports React/Emotion/Ktor/Decompose as `api`, so the SPA only
            // needs the BOM here to keep the wrapper versions aligned.
            implementation(project(":core:frontend"))
            implementation(project.dependencies.platform(libs.kotlinWrappersBom))
        }
    }
}
