plugins {
    id("buildsrc.convention.kotlin-jvm")
    id("buildsrc.convention.publish")
    alias(libs.plugins.kotlinPluginSerialization)
    alias(libs.plugins.koinCompiler)
}

dependencies {
    api(project(":core:shared"))
    api(project(":core:backend"))
    api(project(":storage-postgres"))
    api(project(":transport-rabbit"))

    // Micrometer is required only by MicrometerJobMetrics; user-apps that stick with
    // JobMetrics.Noop don't need to bring a MeterRegistry. Keeping it `implementation`
    // so consumers aren't forced to expose Micrometer on their own API surface.
    implementation(libs.micrometerCore)

    testImplementation(libs.bundles.test)
    testImplementation(libs.bundles.integrationTest)
    testImplementation(project(":engine-infra"))  // for PublishOutboxBatchUseCase tick in tests
    testRuntimeOnly(libs.junitPlatformLauncher)
    testImplementation(libs.logbackClassic)
}
