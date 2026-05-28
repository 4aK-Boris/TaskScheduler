// Entry point of the multi-project build. See DESIGN.md section 3 for the module map.

pluginManagement {
    repositories {
        mavenCentral()
        gradlePluginPortal()
        google {
            mavenContent {
                includeGroupAndSubgroups("androidx")
                includeGroupAndSubgroups("com.android")
                includeGroupAndSubgroups("com.google")
            }
        }
    }
}

dependencyResolutionManagement {
    @Suppress("UnstableApiUsage")
    repositories {
        mavenCentral()
        google {
            mavenContent {
                includeGroupAndSubgroups("androidx")
                includeGroupAndSubgroups("com.android")
                includeGroupAndSubgroups("com.google")
            }
        }
        // Compose Multiplatform dev artifacts (если понадобятся pre-releases)
        maven("https://maven.pkg.jetbrains.space/public/p/compose/dev")
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "TaskScheduler"

// ----- Modules (см. DESIGN.md секция 3) -----

// :core — кросс-модульные примитивы (без 3-layer внутри)
include(":core:shared")
include(":core:backend")
include(":core:frontend")

// Storage + transport — общие для user-app и infra
include(":storage-postgres")
include(":transport-rabbit")

// Optional archival backend — S3-compatible cold storage for the retention loop
// (DESIGN.md 18.7). Opt-in: pulls the AWS SDK only when a deployment needs it.
include(":archival-s3")

// Engine — режется по deployment role
include(":engine-worker")  // ТОЛЬКО в user-app
include(":engine-infra")   // ТОЛЬКО в scheduler-infra container

// Dashboard
include(":dashboard-server")  // Ktor backend, в scheduler-infra
include(":dashboard-web")     // Compose Wasm SPA с Decompose

// Standalone runner — main() для scheduler-infra Docker image
include(":standalone-runner")

// Spring Boot starter — drop-in для пользователей Spring (см. DESIGN/README).
include(":scheduler-spring-boot-starter")

// Demo user app
include(":app")

// K2 compiler plugin for Phase B function-ref lambda capture (DESIGN.md 21.9).
// `scheduler-compiler-plugin` is the kotlinc plugin (depends on kotlin-compiler-embeddable);
// `scheduler-compiler-plugin-gradle` is the Gradle subplugin user apps apply.
include(":scheduler-compiler-plugin")
include(":scheduler-compiler-plugin-gradle")
