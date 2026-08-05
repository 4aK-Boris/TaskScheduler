# TaskScheduler

A JobRunr-alternative, written in Kotlin, for JVM applications that need durable,
distributed background jobs with a built-in web dashboard. PostgreSQL holds the
canonical job state; RabbitMQ delivers work to consumer nodes; an optional
A Kotlin/JS React dashboard provides operator visibility.

Designed to be embedded in your existing application: you pull in the Gradle
modules you need, register your `JobHandler`s via Koin, and stand the
`scheduler-infra` process up alongside.

See [DESIGN.md](DESIGN.md) for the full architecture and rationale; this README
covers what you need to get something running.

## Quick start

```bash
# 1. Build the infra + demo-app images from the shadow JARs (needs a running Docker daemon)
./gradlew :standalone-runner:dockerImage :app:dockerImage

# 2. Bring up Postgres + RabbitMQ + the infra process + a demo app
docker compose up

# 3. Open the dashboard (BasicAuth: admin / admin)
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
| `dashboard-web`       | React dashboard on Kotlin/JS (Decompose nav, dark mode, search, bulk actions) |
| `standalone-runner`   | Ktor host that boots infra + dashboard; this is the `scheduler-infra` JVM|
| `app`                 | Demo user app — shows the integration pattern in ~80 lines               |
| `clients/python`      | Async Python SDK — same wire protocol, for non-JVM services             |

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

## Non-JVM services

Python services join the same job system through [`clients/python`](clients/python/README.md),
which speaks the wire protocol directly rather than proxying through a JVM: it writes the
same `job` and `outbox` rows, claims work with the same lease semantics, and appears on the
same dashboard.

```python
@job_type
@dataclass
class SendInvoice:
    order_id: int

@registry.handler(SendInvoice)
async def send_invoice(ctx: JobContext, job: SendInvoice) -> None:
    await billing.send(job.order_id, idempotency_key=str(ctx.job_id))
```

A `payload_type` is a Kotlin FQN on one side and a Python class name on the other, and a
worker that meets a type it does not know fails the job rather than passing it on — so give
each language its own queues. `scheduler-infra` stays Kotlin either way: it owns the
migrations and the background loops both clients depend on.

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
# Full assembly + browser distribution bundle
./gradlew assemble :dashboard-web:jsBrowserDistribution

# Tests
./gradlew check

# Local infra (without Docker, requires PG + Rabbit already running)
./gradlew :standalone-runner:run
```

This project follows the suggested multi-module setup with a `buildSrc`
convention plugin and a Gradle version catalog (`gradle/libs.versions.toml`).
Build and configuration caches are enabled (`gradle.properties`).

## Docker image & deployment

The `scheduler-infra` process ships as a Docker image. There are two ways to get one:

```bash
# Local build — produces taskscheduler-infra:<version> and :latest (version from gradle.properties)
./gradlew :standalone-runner:dockerImage

# Override the tag / repository name if needed
./gradlew :standalone-runner:dockerImage -Pimage.tag=rc1 -Pimage.name=myorg/scheduler-infra
```

The task builds the shadow JAR (bundling the dashboard bundle) and then reuses
[`docker/infra/Dockerfile`](docker/infra/Dockerfile) — the same Dockerfile CI uses, so local and
published images are identical.

**CI auto-build:** [`.github/workflows/docker.yml`](.github/workflows/docker.yml) builds and pushes
to GHCR on every push to `master` and on `vX.Y.Z` tags:

| Trigger          | Tags pushed                                              |
|------------------|----------------------------------------------------------|
| push to `master` | `:master`, `:sha-<short>`, `:latest`                     |
| tag `v0.2.0`     | `:0.2.0`, `:0.2`, `:latest`                              |

Deploy a published image (no local build needed):

```bash
# via compose
SCHEDULER_INFRA_IMAGE=ghcr.io/4ak-boris/taskscheduler-infra:latest docker compose up

# or standalone
docker run -p 8080:8080 \
  -e POSTGRES_URL=jdbc:postgresql://<host>:5432/scheduler \
  -e POSTGRES_USER=scheduler -e POSTGRES_PASSWORD=... \
  -e RABBITMQ_HOST=<host> -e RABBITMQ_USER=scheduler -e RABBITMQ_PASSWORD=... \
  -e DASHBOARD_AUTH_PASSWORD=... \
  ghcr.io/4ak-boris/taskscheduler-infra:latest
```

Bump the release version in one place — `schedulerVersion` in `gradle.properties` — which drives both
the published library coordinates and the Docker image tag.
