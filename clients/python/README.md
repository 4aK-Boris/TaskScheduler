# TaskScheduler — Python SDK

Async Python client for [TaskScheduler](../../README.md). A Python service becomes a
first-class participant in the same job system as your Kotlin services: it enqueues work,
runs handlers, and shows up on the same dashboard with the same retry, cancellation and
progress semantics.

```python
from dataclasses import dataclass
from taskscheduler import Scheduler, SchedulerConfig, job_type

@job_type
@dataclass
class SendInvoice:
    order_id: int

async with Scheduler(SchedulerConfig(dsn="postgresql://scheduler:scheduler@localhost/scheduler")) as s:
    await s.enqueue(SendInvoice(order_id=42))
```

## How it fits together

The SDK speaks the same wire protocol as the Kotlin client — it is not a proxy in front of
it. PostgreSQL holds all job state; RabbitMQ carries nothing but a 16-byte job id as a
delivery hint.

```
┌──────────────────────────┐        ┌──────────────────────────┐
│  scheduler-infra (Kotlin) │        │  your Python service      │
│  • owns the schema        │        │  • Scheduler.enqueue()    │
│  • outbox → RabbitMQ      │  PG +  │  • WorkerPool runs jobs   │
│  • recurring cron         │ Rabbit │  • heartbeats its lease   │
│  • orphan recovery        │◄──────►│                           │
│  • dashboard :8080        │        │                           │
└──────────────────────────┘        └──────────────────────────┘
             └────────► PostgreSQL ◄────────┘
             └────────► RabbitMQ   ◄────────┘
```

**`scheduler-infra` is required.** It owns the Flyway migrations and the background loops
that neither client implements: publishing the outbox to RabbitMQ, firing cron definitions,
recovering jobs whose worker died, and retention. This SDK verifies the schema version at
startup and refuses to run against a database that is too old.

## Install

```bash
pip install taskscheduler-client        # or: uv pip install taskscheduler-client
```

Requires Python 3.10+, and a RabbitMQ with the `rabbitmq_delayed_message_exchange` plugin
enabled (the same requirement the Kotlin side has — it is how delays and retry backoff work).

**On Windows**, select the other event loop before starting anything — psycopg cannot run on
the `ProactorEventLoop` that asyncio uses by default there:

```python
if sys.platform == "win32":
    asyncio.set_event_loop_policy(asyncio.WindowsSelectorEventLoopPolicy())
```

The SDK raises a `ConfigurationError` naming this if you forget, rather than hanging on a
connection-pool timeout.

## Producing jobs

A payload is a plain dataclass. `@job_type` gives it a stable name that is stored in
`job.payload_type`:

```python
@job_type                       # -> "billing.jobs.SendInvoice"
@dataclass
class SendInvoice:
    order_id: int
    dry_run: bool = False

@job_type("billing.SendInvoice.v2")   # pin it before renaming or moving the class
@dataclass
class SendInvoiceV2:
    ...
```

```python
# now
await scheduler.enqueue(SendInvoice(order_id=42), queue="billing", priority=7)

# at a moment
await scheduler.schedule_at(SendReminder(order_id=42), at=datetime(2026, 6, 1, 10, tzinfo=timezone.utc))
await scheduler.schedule_in(SendReminder(order_id=42), delay=timedelta(days=1))

# at most one active job per key
await scheduler.enqueue_once(f"sync-user-{user_id}", SyncUser(user_id))

# strictly in order
await scheduler.chain(ExtractData(), TransformData(), LoadData())

# after a fan-out finishes
a = await scheduler.enqueue(LoadProductCache())
b = await scheduler.enqueue(LoadUserCache())
await scheduler.enqueue_after(StartPricingEngine(), wait_for=[a, b])

# on a cron
await scheduler.recurring("nightly-rollup", "0 3 * * *", NightlyRollup(), timezone_name="Europe/Berlin")
```

Every enqueue writes the job row and its outbox row in **one transaction**, so a job
becomes visible only if your surrounding business transaction commits.

## Consuming jobs

```python
from taskscheduler import HandlerRegistry, JobContext, RabbitConfig, WorkerConfig, WorkerPool

registry = HandlerRegistry()

@registry.handler(SendInvoice, retry_policy=ExponentialBackoff(max_attempts=5))
async def send_invoice(ctx: JobContext, job: SendInvoice) -> None:
    await billing.send(job.order_id, idempotency_key=str(ctx.job_id))

worker = WorkerPool(
    scheduler_config=SchedulerConfig(dsn=DSN, node_id="billing-1"),
    worker_config=WorkerConfig(node_id="billing-1").queue("billing", concurrency=8),
    rabbit_config=RabbitConfig(url=AMQP_URL, queues=["billing"]),
    registry=registry,
)

async with worker:
    await asyncio.Event().wait()      # run until the process is stopped
```

Class-based handlers work too, when you need `on_final_failure` or dependency injection:

```python
class SendInvoiceHandler(JobHandler[SendInvoice]):
    payload = SendInvoice
    retry_policy = ExponentialBackoff(max_attempts=5)

    def __init__(self, billing: BillingClient) -> None:
        self._billing = billing

    async def execute(self, ctx: JobContext, job: SendInvoice) -> None:
        await self._billing.send(job.order_id)

    async def on_final_failure(self, ctx: JobContext, job: SendInvoice, error: BaseException) -> None:
        await alerts.page(f"invoice {job.order_id} failed permanently")

registry.register(SendInvoiceHandler(billing))
```

### Progress and cancellation

```python
@registry.handler(ReindexCatalog)
async def reindex(ctx: JobContext, job: ReindexCatalog) -> None:
    bar = ctx.progress_bar(len(job.product_ids))
    for product_id in job.product_ids:
        if await ctx.is_cancellation_requested():
            raise JobCancellationError()      # ends as CANCELLED, not FAILED
        await index(product_id)
        await bar.succeeded()
```

Progress writes are throttled to one per second, so calling them in a tight loop is fine.
A cancelled job that ignores the flag is cancelled outright after
`WorkerConfig.cancel_grace_seconds`.

### Failing

| You raise | Outcome |
|---|---|
| any exception | retried per the policy, then FAILED |
| `NonRetriableError` | FAILED immediately, remaining attempts skipped |
| `JobCancellationError` | CANCELLED, no retry, no `on_final_failure` |
| nothing | SUCCEEDED |

## Configuration

```python
SchedulerConfig(
    dsn="postgresql://user:pass@host:5432/scheduler",   # or dsn_from_jdbc(...)
    node_id="billing-1",
    default_queue="default",
    default_max_attempts=3,
    default_timeout_seconds=300,
    default_retry_policy=ExponentialBackoff(max_attempts=3),
)

WorkerConfig(
    node_id="billing-1",
    node_tags=["eu-west"],
    heartbeat_interval_seconds=30,     # must be <= lock_duration / 3
    lock_duration_seconds=90,
    shutdown_timeout_seconds=30,
).queue("billing", concurrency=8, prefetch=8)

RabbitConfig(url="amqp://scheduler:scheduler@localhost:5672/", queues=["billing"])
```

Sharing `POSTGRES_URL` with the Kotlin services:

```python
dsn = dsn_from_jdbc(os.environ["POSTGRES_URL"], os.environ["POSTGRES_USER"], os.environ["POSTGRES_PASSWORD"])
```

## Running Python and Kotlin side by side

Both clients read and write the same tables, and the dashboard shows their jobs together.
What does **not** cross the language boundary is the payload itself: a `payload_type` is a
Python class name here and a Kotlin FQN there, and a worker that picks up a type it does
not know marks the job FAILED rather than passing it on.

**So give each language its own queues.** Point Python handlers at `python`, `ml`, or
whichever names you like, and keep Kotlin workers on theirs:

```python
RabbitConfig(url=AMQP_URL, queues=["ml"])
WorkerConfig(node_id="ml-1").queue("ml", concurrency=4)
```

```kotlin
schedulerRabbitModule { queues = listOf("default", "ml") }   // infra must declare every queue
schedulerWorkerModule { queue("default", concurrency = 8) }  // but only consumes its own
```

The infra process needs every queue name in its `schedulerRabbitModule.queues` so the
topology exists; it does not need to consume them.

If you do want the two languages to run each other's jobs, pin `@job_type("<kotlin FQN>")`
and keep the JSON field names identical to the Kotlin data class — this SDK will encode and
decode it, but nothing checks that the two definitions still agree.

## Guarantees

**At-least-once.** A job can run twice — a lease expiring during a long GC pause, a broker
redelivery, a network partition. `ctx.job_id` is stable across every attempt, so use it as
the idempotency key for anything with side effects:

```python
await payments.charge(order_id, idempotency_key=str(ctx.job_id))
```

**Leases, not locks.** A claimed job is held by `locked_until`, extended every
`heartbeat_interval_seconds`. If this process dies, the lease lapses and infra re-enqueues
the job — that is the recovery path, and it is why `heartbeat_interval` must stay at or
below a third of `lock_duration`.

**Schema evolution.** Adding a field with a default is safe. Removing one is safe — unknown
keys are ignored on decode. Renaming or retyping is not: version the payload
(`SendInvoiceV2`) and keep both handlers until the old jobs have drained. A payload that
cannot be decoded fails terminally without burning retries, since the stored bytes will
never change.

## Development

```bash
uv venv && uv pip install -e ".[dev]"
pytest tests/unit                                    # no infrastructure needed
ruff check src tests examples scripts && mypy src
```

Integration tests need a database with the migrations applied and a broker with the
delayed-message plugin. The whole suite runs in CI on every change under `clients/python`
(`.github/workflows/python-client.yml`); locally, bring the two up yourself:

```bash
docker run -d --name ts-pg -e POSTGRES_USER=scheduler -e POSTGRES_PASSWORD=scheduler \
  -e POSTGRES_DB=scheduler -p 5432:5432 postgres:16-alpine

docker build -t taskscheduler-rabbit ../../docker/rabbitmq
docker run -d --name ts-rabbit -e RABBITMQ_DEFAULT_USER=scheduler \
  -e RABBITMQ_DEFAULT_PASS=scheduler -p 5672:5672 taskscheduler-rabbit

python scripts/apply_migrations.py "postgresql://scheduler:scheduler@localhost:5432/scheduler"

TASKSCHEDULER_TEST_DSN="postgresql://scheduler:scheduler@localhost:5432/scheduler" \
TASKSCHEDULER_TEST_AMQP="amqp://scheduler:scheduler@localhost:5672/" \
  pytest tests/integration
```

`scripts/apply_migrations.py` replays the project's Flyway migrations without a JVM, so the
tests don't need a built `scheduler-infra` image. It is a development shortcut — in a real
deployment `scheduler-infra` owns the schema.

No Kotlin process runs during the tests: an `outbox_pump` fixture stands in for the infra
leader that would otherwise drain the outbox into RabbitMQ. Tests that expect a job to be
delivered more than once (retries, DAG promotions, paused-type redelivery) request it.

Alternatively `docker compose up -d` at the repo root brings up Postgres, RabbitMQ and a
real `scheduler-infra` — closer to production, but it needs the Gradle-built image
(`./gradlew :standalone-runner:dockerImage`).
