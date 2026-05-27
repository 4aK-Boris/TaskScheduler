package cs.trade.scheduler.spring

import org.springframework.boot.context.properties.ConfigurationProperties
import java.util.UUID

/**
 * Spring `application.yml` / `application.properties` binding under the `scheduler.*`
 * prefix. Mirrors the Koin builder DSLs exposed by `schedulerCoreModule`,
 * `schedulerPostgresModule`, `schedulerRabbitModule` and `schedulerWorkerModule`.
 *
 * Example YAML:
 * ```yaml
 * scheduler:
 *   postgres:
 *     url: jdbc:postgresql://localhost:5432/scheduler
 *     user: scheduler
 *     password: scheduler
 *   rabbit:
 *     host: localhost
 *     port: 5672
 *     user: scheduler
 *     password: scheduler
 *   node-id: app-1
 *   worker:
 *     queues:
 *       - default
 *       - email
 *     default-concurrency: 10
 * ```
 *
 * `postgres.url`, `postgres.user`, `postgres.password` are required (the constructor
 * will fail context startup with a clear binding error if absent). Everything else has
 * a sensible default — matches `RunnerConfig.fromEnv` semantics in `standalone-runner`.
 */
@ConfigurationProperties(prefix = "scheduler")
public data class SchedulerProperties(
    val postgres: Postgres,
    val rabbit: Rabbit = Rabbit(),
    /** Node identifier — used by worker registry and outbox publisher attribution. */
    val nodeId: String = "node-${UUID.randomUUID()}",
    /**
     * When true (default), Flyway runs migrations on startup. Keep `false` in user-app
     * deployments where a separate `scheduler-infra` container owns the schema; see
     * `SchedulerPostgresConfig.runMigrations` in :storage-postgres.
     */
    val runMigrations: Boolean = true,
    val worker: Worker = Worker(),
) {
    public data class Postgres(
        val url: String,
        val user: String,
        val password: String,
        /** HikariCP `maximumPoolSize`. Matches the standalone-runner default. */
        val maxPoolSize: Int = 10,
    )

    public data class Rabbit(
        val host: String = "localhost",
        val port: Int = 5672,
        val user: String = "guest",
        val password: String = "guest",
        val vhost: String = "/",
    )

    public data class Worker(
        /** Queue names this app consumes from. The first entry doubles as the default queue. */
        val queues: List<String> = listOf("default"),
        val defaultConcurrency: Int = 10,
        /**
         * When true (default), the starter installs a `SmartLifecycle` that calls
         * `WorkerPool.start()` on Spring startup. Set false if you only want producer-side
         * access to `Scheduler` (e.g. an HTTP API node that enqueues but doesn't process).
         */
        val autoStart: Boolean = true,
    )
}
