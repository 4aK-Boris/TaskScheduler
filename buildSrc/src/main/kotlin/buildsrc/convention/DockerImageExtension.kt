package buildsrc.convention

import org.gradle.api.provider.Property

/**
 * Config for the `dockerImage` task added by the `buildsrc.convention.docker-image` plugin.
 *
 * ```
 * dockerImage {
 *     imageName = "taskscheduler-infra"
 *     dockerfile = "docker/infra/Dockerfile"
 * }
 * ```
 */
interface DockerImageExtension {
    /** Image repository name, e.g. `taskscheduler-infra`. The tag is taken from `schedulerVersion`. */
    val imageName: Property<String>

    /** Dockerfile path relative to the repo root, e.g. `docker/infra/Dockerfile`. */
    val dockerfile: Property<String>
}
