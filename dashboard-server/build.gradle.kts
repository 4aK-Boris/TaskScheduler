plugins {
    id("buildsrc.convention.kotlin-jvm")
    id("buildsrc.convention.publish")
    alias(libs.plugins.kotlinPluginSerialization)
    alias(libs.plugins.koinCompiler)
}

// EventBus + SchedulerCoreConfig are registered via DSL (`single<T> { ... }`) inside
// `schedulerCoreModule` in :core:backend. The Koin compiler plugin's compile-time graph
// validation only traces @Single-annotated declarations, so it can't see those bindings
// from here even though they ARE in the runtime graph. Turn the validation off; the
// runtime graph is exercised in standalone-runner's smoke run.
koinCompiler {
    compileSafety.set(false)
}

dependencies {
    api(project(":core:shared"))
    api(project(":core:backend"))
    api(project(":storage-postgres"))

    api(libs.bundles.ktorServer)
    api(libs.koinKtor)
    api(libs.konform)

    testImplementation(libs.bundles.test)
    testImplementation(libs.ktorServerTestHost)
    testRuntimeOnly(libs.junitPlatformLauncher)
    testImplementation(libs.logbackClassic)
    // End-to-end REST tests boot the real scheduler stack against a Postgres
    // container (or EXTERNAL_PG_URL) — we need the real DefaultScheduler from
    // :storage-postgres, real LeaderElection from :engine-infra, the standalone-runner
    // HealthRouting + auth wiring, and the rabbit ConnectionFactory used by /health/ready.
    testImplementation(libs.bundles.integrationTest)
    testImplementation(libs.amqpClient)
    testImplementation(libs.hikari)
    testImplementation(libs.postgresJdbc)
    testImplementation(libs.flywayCore)
    testImplementation(libs.flywayPostgres)
    testImplementation(project(":engine-infra"))
    testImplementation(project(":transport-rabbit"))
    testImplementation(project(":standalone-runner"))
}
