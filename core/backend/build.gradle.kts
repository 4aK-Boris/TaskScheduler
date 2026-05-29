plugins {
    id("buildsrc.convention.kotlin-jvm")
    id("buildsrc.convention.publish")
    alias(libs.plugins.kotlinPluginSerialization)
    alias(libs.plugins.koinCompiler)
}

dependencies {
    api(project(":core:shared"))
    api(libs.bundles.kotlinxEcosystem)
    api(libs.kotlinxCoroutinesSlf4j)

    // kotlin-reflect — function-ref API (Scheduler.enqueue(KFunction…)) needs KParameter /
    // KType introspection at enqueue time and KCallable.callSuspend at execute time. Pulled
    // here (not in :storage-postgres / :engine-worker individually) so the user-facing
    // typed overloads on Scheduler can reference kotlin.reflect.full.* without each
    // downstream module re-declaring the dep.
    api(libs.kotlinReflect)

    api(libs.slf4jApi)

    // Koin runtime — Compiler Plugin generates module code at compile time (no KSP)
    api(libs.bundles.koinRuntime)

    // Ktor server primitives — для *Handle helpers и ApiResponse в api-слое модулей
    api(libs.ktorServerCore)
    api(libs.ktorServerContentNegotiation)
    api(libs.ktorSerializationKotlinxJson)
    api(libs.ktorServerStatusPages)
    api(libs.ktorServerAuth)

    // Metrics + tracing
    api(libs.micrometerCore)
    api(libs.openTelemetryApi)

    // Cron parsing — used by DefaultScheduler.recurring (storage) and the
    // FireDueRecurringJobs UseCase (engine-infra). Lives in core to stay reusable.
    api(libs.cronUtils)

    testImplementation(libs.bundles.test)
    testRuntimeOnly(libs.junitPlatformLauncher)
    testImplementation(libs.logbackClassic)
}
