package cs.trade.scheduler.runner

import org.slf4j.LoggerFactory

/**
 * Env-driven config for scheduler-infra container. See DESIGN.md section 14.5.
 *
 * Required env vars throw at startup if missing; optional vars use defaults. The structural
 * `requireEnv`/`optEnv` guards in [fromEnv] only catch presence — semantic validation
 * (URL shape, port range, password strength) lives in [validate] so a misconfigured deploy
 * surfaces ALL problems in one shot rather than the operator playing whack-a-mole through
 * N restarts. Call [validate] from `main()` before any DataSource / Koin setup so we fail
 * before doing any side-effecting work (Hikari pool opens TCP connections eagerly).
 */
public data class RunnerConfig(
    val postgresUrl: String,
    val postgresUser: String,
    val postgresPassword: String,
    val rabbitHost: String,
    val rabbitPort: Int,
    val rabbitUser: String,
    val rabbitPassword: String,
    val rabbitVhost: String,
    val dashboardPort: Int,
    val dashboardAuthUser: String,
    val dashboardAuthPassword: String?,
    /**
     * Optional JWT bearer auth (Phase 3). When set alongside [dashboardAuthPassword], BOTH
     * methods work — clients can present either `Authorization: Basic …` or
     * `Authorization: Bearer <token>`. Tokens are validated as HMAC256-signed JWTs whose
     * `iss` / `aud` claims match the configured strings (if any) and whose `sub` claim
     * becomes the [io.ktor.server.auth.UserIdPrincipal] name used by audit attribution.
     *
     * Leaving the secret null disables JWT — BasicAuth alone if set, else open. Setting
     * the secret without leaving issuer/audience null also works (Ktor's plugin accepts
     * any token signed with the right secret).
     */
    val dashboardJwtSecret: String?,
    val dashboardJwtIssuer: String?,
    val dashboardJwtAudience: String?,
    val nodeId: String,
    val runMigrations: Boolean,
    /**
     * Optional S3-compatible archival (DESIGN.md 18.7). `null` → the retention loop deletes
     * terminal rows with no cold-storage copy (`ArchivalSink.Noop`). Set `ARCHIVE_S3_BUCKET`
     * to enable; the `:archival-s3` sink is bundled in this image and bound over the Noop
     * default (Koin last-wins).
     */
    val archiveS3: ArchiveS3Config?,
) {
    /**
     * Settings for the S3-compatible [cs.trade.scheduler.archival.s3.S3ArchivalSink].
     * [accessKeyId]/[secretAccessKey] null → AWS default credential chain (env, profile,
     * instance role). [endpoint] null → real AWS; set it for MinIO / R2 / GCS-S3 / Spaces.
     */
    public data class ArchiveS3Config(
        val bucket: String,
        val region: String,
        val endpoint: String?,
        val accessKeyId: String?,
        val secretAccessKey: String?,
        /** null → auto: path-style when a custom [endpoint] is set, virtual-hosted on AWS. */
        val pathStyleAccess: Boolean?,
        val keyPrefix: String,
    ) {
        public companion object {
            /** Parse `ARCHIVE_S3_*` via an injectable getenv. Returns null when the bucket is unset. */
            public fun fromEnv(get: (String) -> String?): ArchiveS3Config? {
                val bucket = get("ARCHIVE_S3_BUCKET")?.takeIf { it.isNotBlank() } ?: return null
                return ArchiveS3Config(
                    bucket = bucket,
                    region = get("ARCHIVE_S3_REGION")?.takeIf { it.isNotBlank() } ?: "us-east-1",
                    endpoint = get("ARCHIVE_S3_ENDPOINT")?.takeIf { it.isNotBlank() },
                    accessKeyId = get("ARCHIVE_S3_ACCESS_KEY")?.takeIf { it.isNotBlank() },
                    secretAccessKey = get("ARCHIVE_S3_SECRET_KEY")?.takeIf { it.isNotBlank() }
                        ?: get("ARCHIVE_S3_SECRET_KEY_FILE")?.takeIf { it.isNotBlank() }
                            ?.let { java.nio.file.Files.readString(java.nio.file.Path.of(it)).trim() },
                    pathStyleAccess = get("ARCHIVE_S3_PATH_STYLE")?.toBooleanStrictOrNull(),
                    keyPrefix = get("ARCHIVE_S3_KEY_PREFIX")?.takeIf { it.isNotBlank() } ?: "",
                )
            }
        }
    }

    /**
     * Cross-field semantic validation. Accumulates ALL failures into a single
     * `IllegalStateException` so a deploy with three misconfigured env vars only takes one
     * restart cycle to fix instead of three.
     *
     * Weak-password values (`admin` / `password`) only WARN — some local-dev setups
     * intentionally use them and we shouldn't refuse to boot, but they should be loud in
     * the logs so they don't slip into prod unnoticed.
     */
    public fun validate() {
        val failures = mutableListOf<String>()

        // POSTGRES_URL — Flyway + Hikari assume jdbc:postgresql://; a typo like
        // postgresql:// (no jdbc:) silently picks an embedded driver and explodes later.
        if (postgresUrl.isBlank()) {
            failures += "POSTGRES_URL is blank"
        } else if (!postgresUrl.startsWith("jdbc:postgresql://")) {
            failures += "POSTGRES_URL must start with 'jdbc:postgresql://' (got: $postgresUrl)"
        }

        if (postgresUser.isBlank()) failures += "POSTGRES_USER is blank"
        // No length check on POSTGRES_PASSWORD — the DB may legitimately allow short ones,
        // and we don't want to refuse to boot when the DBA gave a 4-char dev password.
        if (postgresPassword.isBlank()) failures += "POSTGRES_PASSWORD is blank"

        if (rabbitHost.isBlank()) failures += "RABBITMQ_HOST is blank"

        // DASHBOARD_PORT — must be a routable TCP port. Negative / zero / >65535 would
        // crash Netty during embeddedServer start, but the trace there is noisy.
        if (dashboardPort !in 1..65_535) {
            failures += "DASHBOARD_PORT must be in 1..65535 (got: $dashboardPort)"
        }

        // DASHBOARD_AUTH_PASSWORD is optional (null disables auth — see Application.kt for
        // the "running without auth" warning), but if set it has to be strong enough that
        // a public deploy isn't trivially brute-forceable.
        val authPass = dashboardAuthPassword
        if (authPass != null) {
            if (authPass.length < MIN_DASHBOARD_PASSWORD_LEN) {
                failures += "DASHBOARD_AUTH_PASSWORD must be at least $MIN_DASHBOARD_PASSWORD_LEN chars " +
                    "(got: ${authPass.length})"
            } else if (authPass.lowercase() in WEAK_PASSWORDS) {
                // WARN, not FAIL — see KDoc above.
                LoggerFactory.getLogger(RunnerConfig::class.java)
                    .warn("DASHBOARD_AUTH_PASSWORD is a well-known weak value — change before exposing this dashboard")
            }
        }

        // JWT secret must be long enough that a brute-force on the HMAC is infeasible. 32
        // bytes (256-bit) is the natural floor for HS256; we soften to 16 chars so dev
        // setups aren't forced to generate 32-char secrets, but warn at the cutoff.
        val jwtSecret = dashboardJwtSecret
        if (jwtSecret != null) {
            if (jwtSecret.length < MIN_JWT_SECRET_LEN) {
                failures += "DASHBOARD_JWT_SECRET must be at least $MIN_JWT_SECRET_LEN chars (got: ${jwtSecret.length})"
            }
        }

        // S3 archival, if enabled. A custom endpoint must carry an http(s):// scheme (the
        // AWS SDK's URI.create silently accepts a bare host and then fails opaquely on first
        // PutObject). Credentials are all-or-nothing — one half set without the other almost
        // always means a typo, and SigV4 with a half-populated key fails cryptically.
        val s3 = archiveS3
        if (s3 != null) {
            if (s3.region.isBlank()) failures += "ARCHIVE_S3_REGION is blank"
            if (s3.endpoint != null && !s3.endpoint.startsWith("http://") && !s3.endpoint.startsWith("https://")) {
                failures += "ARCHIVE_S3_ENDPOINT must start with http:// or https:// (got: ${s3.endpoint})"
            }
            if ((s3.accessKeyId == null) != (s3.secretAccessKey == null)) {
                failures += "ARCHIVE_S3_ACCESS_KEY and ARCHIVE_S3_SECRET_KEY must be set together " +
                    "(or both omitted to use the AWS default credential chain)"
            }
        }

        if (failures.isNotEmpty()) {
            error(
                "RunnerConfig validation failed (${failures.size} issue(s)):\n" +
                    failures.joinToString("\n") { "  - $it" }
            )
        }
    }

    public companion object {
        /** OWASP-floor for a basic-auth secret. Real prod should use a passphrase / vault. */
        public const val MIN_DASHBOARD_PASSWORD_LEN: Int = 8

        /** Floor for the JWT HMAC256 secret. 16 chars is below the cryptographic ideal of 32, but is the smallest still credible against casual brute-force. */
        public const val MIN_JWT_SECRET_LEN: Int = 16

        /** Lowercased shortlist — case-insensitive match. Extend as bad defaults appear in incident reports. */
        private val WEAK_PASSWORDS: Set<String> = setOf("admin", "password")

        public fun fromEnv(): RunnerConfig = RunnerConfig(
            postgresUrl = requireEnv("POSTGRES_URL"),
            postgresUser = requireEnv("POSTGRES_USER"),
            postgresPassword = readPasswordEnv("POSTGRES_PASSWORD"),
            rabbitHost = requireEnv("RABBITMQ_HOST"),
            rabbitPort = optEnv("RABBITMQ_PORT", "5672").toInt(),
            rabbitUser = requireEnv("RABBITMQ_USER"),
            rabbitPassword = readPasswordEnv("RABBITMQ_PASSWORD"),
            rabbitVhost = optEnv("RABBITMQ_VHOST", "/"),
            dashboardPort = optEnv("DASHBOARD_PORT", "8080").toInt(),
            dashboardAuthUser = optEnv("DASHBOARD_AUTH_USER", "admin"),
            dashboardAuthPassword = System.getenv("DASHBOARD_AUTH_PASSWORD"),
            dashboardJwtSecret = System.getenv("DASHBOARD_JWT_SECRET"),
            dashboardJwtIssuer = System.getenv("DASHBOARD_JWT_ISSUER"),
            dashboardJwtAudience = System.getenv("DASHBOARD_JWT_AUDIENCE"),
            nodeId = optEnv("NODE_ID", "infra-${java.net.InetAddress.getLocalHost().hostName}"),
            runMigrations = optEnv("RUN_MIGRATIONS", "true").toBoolean(),
            archiveS3 = ArchiveS3Config.fromEnv(System::getenv),
        )

        private fun requireEnv(name: String): String =
            System.getenv(name) ?: error("Env var $name is required")

        private fun optEnv(name: String, default: String): String =
            System.getenv(name) ?: default

        /** Read VAR or VAR_FILE (Docker secrets pattern). */
        private fun readPasswordEnv(name: String): String {
            System.getenv(name)?.let { return it }
            System.getenv("${name}_FILE")?.let { file ->
                return java.nio.file.Files.readString(java.nio.file.Path.of(file)).trim()
            }
            error("Env var $name or ${name}_FILE is required")
        }
    }
}
