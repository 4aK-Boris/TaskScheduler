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

    api(libs.cronUtils)

    testImplementation(libs.bundles.test)
    testImplementation(libs.bundles.integrationTest)
    testRuntimeOnly(libs.junitPlatformLauncher)
    testImplementation(libs.logbackClassic)
}
