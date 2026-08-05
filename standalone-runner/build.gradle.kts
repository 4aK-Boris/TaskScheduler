import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar

plugins {
    id("buildsrc.convention.kotlin-jvm")
    id("buildsrc.convention.docker-image")
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
    // Bundled into the prebuilt scheduler-infra image so S3 archival can be switched on
    // via ARCHIVE_S3_* env vars (DESIGN.md 18.7 / 14.5). The library modules stay SDK-free;
    // only this deployment artifact carries the AWS SDK.
    implementation(project(":archival-s3"))

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

// Copy the :dashboard-web JS bundle into our resources so Ktor can serve it as static.
// See DESIGN.md section 15.5.
tasks.processResources {
    val webDistDir = project(":dashboard-web")
        .layout.buildDirectory.dir("dist/js/productionExecutable")
    from(webDistDir) {
        into("dashboard-web")
    }
    dependsOn(":dashboard-web:jsBrowserDistribution")
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

// `./gradlew :standalone-runner:dockerImage` builds the scheduler-infra image straight from the
// shadow JAR above (build context = build/libs). Same Dockerfile the CI workflow uses.
dockerImage {
    imageName = "taskscheduler-infra"
    dockerfile = "docker/infra/Dockerfile"
}


