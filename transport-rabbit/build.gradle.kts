plugins {
    id("buildsrc.convention.kotlin-jvm")
    alias(libs.plugins.kotlinPluginSerialization)
    alias(libs.plugins.koinCompiler)
}

dependencies {
    api(project(":core:shared"))
    api(project(":core:backend"))

    api(libs.amqpClient)

    testImplementation(libs.bundles.test)
    testRuntimeOnly(libs.junitPlatformLauncher)
    testImplementation(libs.bundles.integrationTest)
    testImplementation(libs.logbackClassic)
}
