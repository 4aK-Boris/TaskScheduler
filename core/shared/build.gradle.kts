plugins {
    id("buildsrc.convention.kotlin-multiplatform")
    alias(libs.plugins.kotlinPluginSerialization)
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation(libs.bundles.kotlinxEcosystem)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}
