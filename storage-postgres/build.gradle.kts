plugins {
    id("buildsrc.convention.kotlin-jvm")
    id("buildsrc.convention.publish")
    alias(libs.plugins.kotlinPluginSerialization)
    alias(libs.plugins.koinCompiler)
}

dependencies {
    api(project(":core:shared"))
    api(project(":core:backend"))

    api(libs.postgresJdbc)
    api(libs.hikari)
    api(libs.bundles.exposed)
    api(libs.bundles.flyway)

    testImplementation(libs.bundles.test)
    testRuntimeOnly(libs.junitPlatformLauncher)
    testImplementation(libs.bundles.integrationTest)
    testImplementation(libs.logbackClassic)
}
