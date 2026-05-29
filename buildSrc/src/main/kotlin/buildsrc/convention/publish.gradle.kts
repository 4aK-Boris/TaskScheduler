// Convention: publish a library module to a Maven repository.
//
// Wired for **mavenLocal only** — `./gradlew publishToMavenLocal` drops the artifacts into
// ~/.m2 so a SEPARATE consumer project (e.g. a Ktor + Koin app) can depend on
// `cs.trade.scheduler:<module>:<version>` by coordinates instead of a Gradle composite build.
// See docs/INTEGRATION.md §2.
//
// Applied only to the library modules a consumer needs — NOT to the apps (:app,
// :standalone-runner) or the wasm UI (:dashboard-web, :core:frontend).
package buildsrc.convention

plugins {
    `maven-publish`
}

group = "cs.trade.scheduler"
// SNAPSHOT so a re-publish during local iteration is picked up without the consumer
// needing --refresh-dependencies (Gradle re-checks SNAPSHOTs; release versions are cached).
version = "0.1.0-SNAPSHOT"

// JVM modules (kotlin("jvm")) get no publication for free — create one from the `java`
// component. KMP modules (kotlin("multiplatform")) auto-register a publication per target
// the moment `maven-publish` is applied, so we must NOT create one there (it would clash).
pluginManager.withPlugin("org.jetbrains.kotlin.jvm") {
    publishing {
        publications {
            create<MavenPublication>("maven") {
                from(components["java"])
            }
        }
    }
}

// Normalise artifactId to the project PATH so nested modules stay unambiguous:
//   :core:backend     -> core-backend
//   :core:shared      -> core-shared  (KMP root) + core-shared-jvm / core-shared-wasm-js
//   :storage-postgres -> storage-postgres
// `configureEach` is lazy, so it also catches the KMP target publications the multiplatform
// plugin creates later in its own afterEvaluate. `project.name` (not the bare `name`, which
// would be the publication name) is the Gradle module name = the default artifactId base.
val artifactBase = path.removePrefix(":").replace(":", "-")
publishing {
    publications.withType<MavenPublication>().configureEach {
        artifactId = artifactBase + artifactId.removePrefix(project.name)
    }

    // GitHub Packages — чтобы CI потребителя (CsTradeService) резолвил либу по сети на обоих
    // раннерах (ubuntu-latest + self-hosted). Пакеты публикуются ПОД репозиторий
    // 4aK-Boris/CsTradeService, поэтому его CI читает их штатным GITHUB_TOKEN (same-repo) —
    // без отдельного PAT-секрета. Для ЛОКАЛЬНОЙ публикации (`./gradlew publish`) нужен PAT с
    // write:packages в ~/.gradle/gradle.properties → gpr.user=4aK-Boris  gpr.token=<PAT>.
    // `publishToMavenLocal` для локальной разработки продолжает работать как прежде.
    repositories {
        maven {
            name = "GitHubPackages"
            url = uri("https://maven.pkg.github.com/4aK-Boris/CsTradeService")
            credentials {
                username = providers.gradleProperty("gpr.user").orNull ?: System.getenv("GITHUB_ACTOR")
                password = providers.gradleProperty("gpr.token").orNull ?: System.getenv("GITHUB_TOKEN")
            }
        }
    }
}
