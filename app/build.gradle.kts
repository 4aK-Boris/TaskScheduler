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
    implementation(project(":engine-worker"))

    implementation(libs.koinCore)
    implementation(libs.koinSlf4j)
    implementation(libs.logbackClassic)

    testImplementation(libs.bundles.test)
    testRuntimeOnly(libs.junitPlatformLauncher)

    // Function-ref lambda capture compiler plugin (DESIGN.md 21.9). Activated ONLY for the
    // test compilation so SchedulerLambdaCaptureTest can exercise the real IR rewrite of
    // `enqueueLambda { … }`. The plugin self-registers via its META-INF/services
    // CompilerPluginRegistrar once it is on the kotlinc plugin classpath — no -Xplugin flag.
    "kotlinCompilerPluginClasspathTest"(project(":scheduler-compiler-plugin"))
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

// `./gradlew :app:dockerImage` builds the demo-app image so `docker compose up` has both halves.
dockerImage {
    imageName = "taskscheduler-demo-app"
    dockerfile = "docker/app/Dockerfile"
}

