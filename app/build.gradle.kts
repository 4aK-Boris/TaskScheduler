import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar

plugins {
    id("buildsrc.convention.kotlin-jvm")
    application
    alias(libs.plugins.kotlinPluginSerialization)
    alias(libs.plugins.koinCompiler)
    alias(libs.plugins.shadow)
}

dependencies {
    implementation(project(":core:shared"))
    implementation(project(":core:backend"))
    implementation(project(":storage-postgres"))
    implementation(project(":transport-rabbit"))
    implementation(project(":engine-worker"))

    implementation(libs.koinCore)
    implementation(libs.koinSlf4j)
    implementation(libs.logbackClassic)

    testImplementation(libs.bundles.test)
    testRuntimeOnly(libs.junitPlatformLauncher)
}

application {
    mainClass = "cs.trade.scheduler.demo.DemoAppKt"
}

// Fat-jar for the demo container — docker/app/Dockerfile copies it as app-all.jar.
tasks.named<ShadowJar>("shadowJar") {
    archiveBaseName.set("app")
    archiveClassifier.set("all")
    archiveVersion.set("")
    mergeServiceFiles()
    manifest {
        attributes["Main-Class"] = "cs.trade.scheduler.demo.DemoAppKt"
    }
}

