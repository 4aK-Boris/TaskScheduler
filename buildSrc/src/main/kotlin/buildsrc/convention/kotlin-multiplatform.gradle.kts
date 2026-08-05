// Convention for Kotlin Multiplatform modules with jvm + js targets.
// Used by :core:shared. Other KMP modules can extend or override.
//
// The js target is the browser half of the dashboard: :core:shared DTOs are consumed both by
// the Ktor server (jvm) and by the React SPA (js). It is js(IR), not wasmJs — the React
// wrappers (kotlin-react/kotlin-emotion) publish js variants only.
package buildsrc.convention

import org.gradle.api.tasks.testing.logging.TestLogEvent

plugins {
    kotlin("multiplatform")
}

kotlin {
    jvmToolchain(21)
    explicitApi()

    jvm()

    js(IR) {
        browser()
        binaries.library()
    }
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    testLogging {
        events(
            TestLogEvent.FAILED,
            TestLogEvent.PASSED,
            TestLogEvent.SKIPPED,
        )
    }
}
