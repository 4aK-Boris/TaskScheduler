plugins {
    id("buildsrc.convention.kotlin-jvm")
    alias(libs.plugins.kotlinPluginSerialization)
}

// Spring Boot starter that exposes the scheduler to Spring users without forcing them
// through Koin. The starter boots a private Koin context internally and republishes the
// Koin-built singletons (Scheduler, JobRepository, WorkerPool) as Spring beans. See
// README — Spring Boot integration section.
dependencies {
    api(project(":core:shared"))
    api(project(":core:backend"))
    api(project(":storage-postgres"))
    api(project(":transport-rabbit"))
    api(project(":engine-worker"))

    // Koin core — the starter calls startKoin {} directly.
    implementation(libs.koinCore)

    // Spring Boot autoconfig + Spring context. autoconfigure pulls in spring-context, but
    // we list it explicitly so `@AutoConfiguration` / `SmartLifecycle` imports resolve in
    // IDE inspections too. spring-boot-starter is the runtime aggregator that user apps
    // inherit transitively.
    api(libs.springBootAutoConfigure)
    api(libs.springBootStarter)
    api(libs.springContext)
    // Generates META-INF/spring-configuration-metadata.json for IDE completion of
    // `scheduler.*` keys in application.yml. annotationProcessor scope so it doesn't
    // ship in the runtime jar.
    annotationProcessor(libs.springBootConfigurationProcessor)

    testImplementation(libs.bundles.test)
    testRuntimeOnly(libs.junitPlatformLauncher)
    testImplementation(libs.bundles.integrationTest)
    testImplementation(libs.springBootTest)
    // AssertJ ships in `spring-boot-test` only as a `compileOnly` dep on Boot 3.x; the
    // `ApplicationContextRunner.run { ... }` callback parameter type extends
    // `AssertProvider`, so without it on the compile classpath Kotlin can't even resolve
    // the lambda. Pin a current 3.x version explicitly.
    testImplementation("org.assertj:assertj-core:3.27.3")
    testImplementation(libs.logbackClassic)
}
