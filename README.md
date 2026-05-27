# TaskScheduler

A JobRunr-alternative, written in Kotlin, for JVM applications that need durable,
distributed background jobs with a built-in web dashboard. PostgreSQL holds the
canonical job state; RabbitMQ delivers work to consumer nodes; an optional
Compose Wasm dashboard provides operator visibility.

Designed to be embedded in your existing application: you pull in the Gradle
modules you need, register your `JobHandler`s via Koin, and stand the
`scheduler-infra` process up alongside.

See [DESIGN.md](DESIGN.md) for the full architecture and rationale; this README
covers what you need to get something running.

## Quick start

```bash
# 1. Bring up Postgres + RabbitMQ + the infra process + a demo app
docker compose up --build

# 2. Open the dashboard (BasicAuth: admin / admin)
open http://localhost:8080
```

The `app` container enqueues one `SendEmail` job at startup so you can see the
end-to-end flow in the dashboard the moment everything boots.

## Modules

| Module                | Purpose                                                                  |
|-----------------------|--------------------------------------------------------------------------|
| `core/backend`        | `Scheduler` API, `JobHandler` SPI, retry policies, domain entities       |
| `core/shared`         | Cross-target DTOs (`@Serializable`), enums (`JobState`, `RerouteResult`) |
| `core/frontend`       | KMP base for browser UI (`BaseComponent`, `ApiClient`)                   |
| `storage-postgres`    | Exposed 1.x repos, Flyway migrations, outbox writer                      |
| `transport-rabbit`    | Rabbit publisher + consumer, delayed-message-exchange support            |
| `engine-infra`        | Outbox publisher, leader election, recurring scheduler, fast-forward     |
| `engine-worker`       | Worker pool that pulls from Rabbit and invokes `JobHandler`s             |
| `dashboard-server`    | REST + WS endpoints (`/api/jobs/*`, `/api/ws/events`, …)                 |
| `dashboard-web`       | Compose Wasm dashboard (Decompose nav, dark mode, search, bulk actions)  |
| `standalone-runner`   | Ktor host that boots infra + dashboard; this is the `scheduler-infra` JVM|
| `app`                 | Demo user app — shows the integration pattern in ~80 lines               |

## Integrating into your application

Your user-side code only depends on `core/backend`, `core/shared`,
`storage-postgres`, `transport-rabbit`, and `engine-worker`. The infra process
(`standalone-runner`) runs separately — typically one or more replicas in your
cluster — and owns the heavy loops (outbox publisher, recurring schedule, leader
election).

```kotlin
fun main() = runBlocking {
    val ds = HikariDataSource(HikariConfig().apply {
        jdbcUrl = "jdbc:postgresql://postgres:5432/scheduler"
        username = "scheduler"; password = "scheduler"
        // PG quirk: needed so JSONB INSERTs of `String` payload don't fail.
        addDataSourceProperty("stringtype", "unspecified")
    })
    val rabbit = ConnectionFactory().apply {
        host = "rabbitmq"; username = "scheduler"; password = "scheduler"
        isAutomaticRecoveryEnabled = true
    }

    startKoin {
        modules(
            schedulerCoreModule {
                nodeId = "billing-worker-1"
                defaultRetryPolicy = ExponentialBackoff(maxAttempts = 5)
            },
            schedulerPostgresModule {
                dataSource = ds
                runMigrations = false       // infra owns the schema
            },
            schedulerRabbitModule {
                connectionFactory = rabbit
                queues = listOf("default", "billing")
            },
            schedulerWorkerModule {
                nodeId = "billing-worker-1"
                lockDuration = 60.seconds
                queue("default", concurrency = 8)
                queue("billing", concurrency = 4)
            },
            module { single { SendInvoiceHandler() } bind JobHandler::class },
        )
    }

    val koin = GlobalContext.get()
    koin.get<WorkerPool>().start()

    val scheduler = koin.get<Scheduler>()
    scheduler.enqueue(SendInvoice(orderId = 42L))
    scheduler.schedule(SendReminder(orderId = 42L), at = Clock.System.now() + 1.days)
    // Recurring: cron-utils expressions, registered once at boot.
    scheduler.recurring("nightly-rollups", "0 3 * * *") { NightlyRollup() }

    Thread.currentThread().join()
}

@Serializable
data class SendInvoice(val orderId: Long) : Payload

class SendInvoiceHandler : JobHandler<SendInvoice> {
    override suspend fun handle(payload: SendInvoice, ctx: JobContext) {
        // your business logic — exceptions trigger the configured retry policy
    }
}
```

For the full demo see [`app/src/main/kotlin/cs/trade/scheduler/demo/DemoApp.kt`](app/src/main/kotlin/cs/trade/scheduler/demo/DemoApp.kt).

## Operability

The `scheduler-infra` JVM exposes:

- `GET /health/live`   — liveness, always 200 when the JVM is up.
- `GET /health/ready`  — `{db, rabbit}` with 503 on either failure (DB ping 1s, Rabbit handshake 2s).
- `GET /health/leader` — `{leader: true|false}`, which replica currently holds the PG advisory lock.
- `GET /metrics`       — Prometheus scrape target (Micrometer).
- `GET /` + `/api/*`   — the dashboard UI and its REST/WS surface (BasicAuth gated).

Multi-replica deployments are first-class: `LeaderElection` uses
`pg_try_advisory_lock` so only one replica runs the outbox publisher, recurring
scheduler, and fast-forward loop at a time. Dashboard reads are served by any
replica.

## Build

```bash
# Full assembly + Wasm distribution bundle
./gradlew assemble :dashboard-web:wasmJsBrowserDistribution

# Tests
./gradlew check

# Local infra (without Docker, requires PG + Rabbit already running)
./gradlew :standalone-runner:run
```

This project follows the suggested multi-module setup with a `buildSrc`
convention plugin and a Gradle version catalog (`gradle/libs.versions.toml`).
Build and configuration caches are enabled (`gradle.properties`).
