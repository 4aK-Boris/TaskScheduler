// Optional S3-compatible ArchivalSink (DESIGN.md 18.7). Kept as its own module so the
// AWS SDK only lands on the classpath of deployments that actually want cloud archival —
// the core scheduler stays SDK-free. Works against AWS S3, MinIO, Cloudflare R2, GCS (S3
// API), DigitalOcean Spaces, … via endpoint override + path-style access.

plugins {
    id("buildsrc.convention.kotlin-jvm")
    id("buildsrc.convention.publish")
}

dependencies {
    api(project(":core:backend"))   // ArchivalSink contract
    api(project(":core:shared"))    // ArchivedJobRecord wire model

    implementation(libs.kotlinxSerialization)
    implementation(libs.kotlinxCoroutinesCore)
    implementation(libs.slf4jApi)

    // AWS SDK v2 — BOM pins the version; netty async client excluded (we use the sync
    // S3Client over url-connection-client, the lightest HTTP impl).
    implementation(platform(libs.awsBom))
    implementation(libs.awsS3) {
        exclude(group = "software.amazon.awssdk", module = "netty-nio-client")
    }
    implementation(libs.awsUrlConnectionClient)

    testImplementation(libs.bundles.test)
    testRuntimeOnly(libs.junitPlatformLauncher)
    testImplementation(libs.testcontainers)
    testImplementation(libs.logbackClassic)
}
