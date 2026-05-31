// Convention: build a Docker image for a deployable fat-JAR module (e.g. :standalone-runner, :app).
//
// Reuses the hand-written Dockerfile under docker/<x>/ as the SINGLE source of truth, so the local
// `./gradlew :<module>:dockerImage` task and the GitHub Actions workflow (.github/workflows/docker.yml)
// build byte-identical images. The build context is the module's build/libs dir — only the shadow JAR
// is sent to the daemon, so no repo-root .dockerignore is needed and the context stays tiny.
//
// Requires a running Docker daemon. The image is tagged `<imageName>:<schedulerVersion>` + `:latest`.
// Override the tag with `-Pimage.tag=...` (CI passes the git tag / branch via metadata-action instead).
package buildsrc.convention

import org.gradle.api.tasks.Exec

val imageSpec = extensions.create<DockerImageExtension>("dockerImage")

// Resolved at configuration time (config-cache friendly): -Pimage.tag wins, else the single-source
// version from gradle.properties, else a "dev" fallback so the task never produces an untagged image.
val imageTag: String = providers.gradleProperty("image.tag")
    .orElse(providers.gradleProperty("schedulerVersion"))
    .getOrElse("dev")

// Captured as a File at apply time (serializable) so the task body never touches `rootProject` at
// execution time — keeps the Exec task compatible with the configuration cache.
val repoRoot = project.rootDir

tasks.register<Exec>("dockerImage") {
    group = "docker"
    description = "Builds this module's Docker image from its shadow JAR (needs a running Docker daemon)."
    dependsOn(tasks.named("shadowJar"))

    val dockerfile = repoRoot.resolve(imageSpec.dockerfile.get())
    val contextDir = layout.buildDirectory.dir("libs").get().asFile
    val repo = imageSpec.imageName.get()

    commandLine(
        "docker", "build",
        "-f", dockerfile.absolutePath,
        "-t", "$repo:$imageTag",
        "-t", "$repo:latest",
        contextDir.absolutePath,
    )
}
