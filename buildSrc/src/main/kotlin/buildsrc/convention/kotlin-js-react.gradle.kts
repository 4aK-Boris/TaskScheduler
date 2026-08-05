// Convention for the browser-side Kotlin/JS modules — :core:frontend (library) and
// :dashboard-web (application). Both render with React via the JetBrains kotlin-wrappers.
//
// Why js(IR) and not wasmJs: kotlin-react / kotlin-emotion publish `js` variants only, so the
// React SPA cannot be a wasm binary. Modules add their own dependencies (including the
// `kotlinWrappersBom` platform) in their build.gradle.kts.
//
// `explicitApi()` is deliberately NOT enabled here: React component declarations
// (`external interface Props`, `val Screen = FC<Props> { ... }`) would each need an explicit
// return type, which buys nothing inside an application UI layer.
package buildsrc.convention

plugins {
    kotlin("multiplatform")
}

kotlin {
    jvmToolchain(21)

    js(IR) {
        browser()
    }
}
