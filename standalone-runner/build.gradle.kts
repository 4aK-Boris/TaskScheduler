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
    implementation(project(":engine-infra"))
    implementation(project(":dashboard-server"))

    implementation(libs.bundles.ktorServer)
    implementation(libs.ktorServerMetricsMicrometer)
    implementation(libs.micrometerPrometheus)
    implementation(libs.koinCore)
    implementation(libs.koinKtor)
    implementation(libs.koinSlf4j)
    implementation(libs.logbackClassic)

    testImplementation(libs.bundles.test)
    testRuntimeOnly(libs.junitPlatformLauncher)
    testImplementation(libs.bundles.integrationTest)
    testImplementation(libs.ktorServerTestHost)
}

application {
    mainClass = "cs.trade.scheduler.runner.ApplicationKt"
}

// Copy :dashboard-web wasmJs bundle into our resources so Ktor can serve it as static.
// See DESIGN.md section 15.5.
tasks.processResources {
    val webDistDir = project(":dashboard-web")
        .layout.buildDirectory.dir("dist/wasmJs/productionExecutable")
    from(webDistDir) {
        into("dashboard-web")
    }
    dependsOn(":dashboard-web:wasmJsBrowserDistribution")
}

// Fat-jar built by `./gradlew :standalone-runner:shadowJar` — the Dockerfile in
// docker/infra/ copies it as standalone-runner-all.jar.
tasks.named<ShadowJar>("shadowJar") {
    archiveBaseName.set("standalone-runner")
    archiveClassifier.set("all")
    archiveVersion.set("")
    mergeServiceFiles()
    manifest {
        attributes["Main-Class"] = "cs.trade.scheduler.runner.ApplicationKt"
    }
}


