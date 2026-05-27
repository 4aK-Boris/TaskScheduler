plugins {
    `kotlin-dsl`
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    // Plugins applied by convention scripts in src/main/kotlin
    implementation(libs.kotlinGradlePlugin)
    implementation(libs.composeGradlePlugin)
    implementation(libs.composeCompilerGradlePlugin)
}
