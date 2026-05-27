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
    val nodeId: String,
    val runMigrations: Boolean,
) {
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
            nodeId = optEnv("NODE_ID", "infra-${java.net.InetAddress.getLocalHost().hostName}"),
            runMigrations = optEnv("RUN_MIGRATIONS", "true").toBoolean(),
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
