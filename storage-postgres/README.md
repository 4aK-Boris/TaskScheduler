# storage-postgres

Postgres-backed `StorageProvider`: nine tables, nine repositories, Flyway migrations,
plus the production `DefaultScheduler` (enqueue / chain / barrier / cron) and a
`PostgresEventBus` for cross-replica dashboard fan-out. Targets **Exposed 1.x** — the
`org.jetbrains.exposed.v1.*` repackaging, not 0.x.

For the big picture (state machine, schema columns, retention strategy) see
[DESIGN.md](../DESIGN.md) sections 5 (state machine), 6 (schema columns), 7
(critical flows), 17 (idempotency), 18 (retention).

## What this module does

Single source of truth for job state. Every state transition (enqueue, pickup,
heartbeat, finalize, retry-schedule, cancel-request, recurring-trigger, reroute) goes
through here. Engine modules (`:engine-worker`, `:engine-infra`) and the dashboard
(`:dashboard-server`) all depend on this module's domain interfaces — only this module
knows about JDBC, Exposed, Flyway, and the JSONB encoding.

## Schema overview

Nine tables. See [V1__initial_schema.sql](src/main/resources/db/migration/V1__initial_schema.sql)
for the authoritative DDL and per-column comments.

| Table | Purpose | CASCADE? |
|---|---|---|
| `job` | the core entity. One row per enqueue, full state machine + DAG metadata. | — |
| `outbox` | transactional outbox (DESIGN.md 4.1). Drained by `OutboxPublisher` in infra. | from `job` |
| `job_event` | audit timeline: ENQUEUED / STARTED / SUCCEEDED / FAILED / RETRY / TIMEOUT / CANCELLED / MANUAL_*. Drives the dashboard's Job Detail timeline. | from `job` |
| `recurring_job` | cron definitions. `next_trigger_at` is bumped by `FireDueRecurringJobsUseCase` after each fire. | — |
| `worker` | heartbeat registry for user-app worker nodes. Read by dashboard for the alive set. | — |
| `idempotency_log` | handler-side dedup (`PostgresIdempotencyStore.tryMark`). PK = `(job_id, action)`. **No FK to `job`** — see retention quirk below. | — |
| `job_dependency` | DAG edges parent→child + `on_failure` rule (PROPAGATE_FAILURE / CANCEL_CHILD / IGNORE). PK = `(parent_id, child_id)` so duplicates are no-ops. | from `job` both sides |
| `job_type_pause` | "feature flag" — which `payload_type` values are paused. Checked by both `OutboxPublisher` and `WorkerPool`. | — |
| `job_rollup` | parent-of-many progress aggregation: when a barrier parent has N leaves, the rollup row tracks SUCCEEDED count for UI progress bars without a SUM-on-read. | from `job` |

### Indexes worth knowing

| Index | Why |
|---|---|
| `job (state, scheduled_at)` | fast-forward + worker pickup paths |
| `job (state, locked_until)` | safety-net orphan recovery scan |
| `job (payload_type, state)` | dashboard filters + pause checks |
| `job_idempotency_key_active_idx` | UNIQUE partial — enforces `enqueueOnce(key)` only against non-terminal rows (DESIGN.md 17.4) |
| `outbox_unpublished_idx` | partial on `published_at IS NULL` — keeps `OutboxPublisher.pollUnpublished` cheap as the table fills |
| `recurring_job_next_trigger_idx` | partial on `enabled = TRUE` — fire-due query skips disabled rows |

## Repositories pattern

Every table has a domain interface (`domain/repositories/*Repository`) and an Exposed
impl (`infrastructure/repositories/*RepositoryImpl`). Interfaces live in `domain/` so
upstream modules (`:engine-worker` etc.) can take them as constructor deps without
depending on Exposed.

```
domain/
  StorageProvider.kt                — aggregator handle for the nine repos
  models/                           — row data classes (Job, JobEventRow, …)
  repositories/                     — interfaces only
infrastructure/
  PostgresStorageProvider.kt        — concrete bag-of-repos
  tables/                           — Exposed Table objects (JobTable, OutboxTable, …)
  repositories/                     — *RepositoryImpl, the Exposed bodies
  scheduler/
    DefaultScheduler.kt             — enqueue / chain / barrier / recurring / reroute
    PostgresIdempotencyStore.kt     — IdempotencyStore impl on idempotency_log
  events/
    PostgresEventBus.kt             — LISTEN/NOTIFY backplane for dashboard events
  archival/
    ArchivedJobMapper.kt            — row → ArchivedJob for the optional ArchivalSink
  TimeMappers.kt                    — kotlin.time.Instant ↔ Exposed OffsetDateTime
  SchedulerPostgresModule.kt        — Koin DSL: schedulerPostgresModule { … }
src/main/resources/db/migration/
  V1__initial_schema.sql            — DDL for all nine tables + indexes
  V2__worker_in_flight_by_queue.sql — adds worker.in_flight_by_queue JSONB
  V3__job_initial_pending_deps.sql  — adds job.initial_pending_deps for rollup
  V4__job_rollup.sql                — job_rollup table
```

Repos return domain rows, never `Iterable<ResultRow>` — the mapping happens at the
repository boundary so call sites never touch Exposed types.

## Exposed 1.x quirks

1. **`org.jetbrains.exposed.v1.*` repackaging.** The 0.x→1.x rewrite moved everything
   from `org.jetbrains.exposed.sql.*` to `org.jetbrains.exposed.v1.{core,jdbc,…}`.
   Imports are NOT one-to-one — `select`, `update`, `insert`, `deleteWhere` live in
   `org.jetbrains.exposed.v1.jdbc.*`; `eq`, `inList`, `and`, `greaterEq` live in
   `org.jetbrains.exposed.v1.core.*`. Don't try to copy import lists from an Exposed 0.x
   project; check `JobRepositoryImpl.kt` for the canonical set we use.

2. **`uuid()` returns `kotlin.uuid.Uuid`, not `java.util.UUID`.** No bridge conversion
   needed at the repository boundary. Domain models use `kotlin.uuid.Uuid`; mappers in
   tests / DTOs convert at the edge. Annotate any file that touches `Uuid` with
   `@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)` — the Kotlin compiler still
   gates it behind an opt-in.

3. **`SqlExpressionBuilder` is gone.** `eq` / `isNull` / `less` etc. are top-level
   functions in `org.jetbrains.exposed.v1.core` now. **DO NOT** import the legacy
   `SqlExpressionBuilder.eq` etc. — those are 0.x. Code that reads `JobTable.state eq
   "PROCESSING"` works because Kotlin resolves the top-level `eq` extension on the
   column receiver.

4. **`selectAll().where { … }`, not `select { … }`.** 1.x split the read API:
   `select(columns).where { … }` for a column projection, `selectAll().where { … }`
   for a full-row read. `JobRepositoryImpl` shows both.

5. **`suspendTransaction { … }` is the only entry point we use.** Nested calls reuse
   the outer coroutine-context transaction automatically — `Scheduler.enqueue` opens
   one and the inner `jobs.insert + outbox.insert + events.insert` all join that
   transaction without spinning up new connections. Don't add `newSuspendedTransaction`
   anywhere; it would defeat the composition.

6. **`stringtype=unspecified` on the DataSource is non-negotiable.** PgJDBC defaults
   String parameters to VARCHAR; INSERT into a JSONB column (`payload_json`,
   `context_json`) then fails with `column "payload_json" is of type jsonb but
   expression is of type character varying`. The Hikari config in
   `Application.kt` sets it; user-apps embedding the library must do the same. See
   `SchedulerPostgresModule.kt` KDoc.

## Flyway migrations

Migrations live in `src/main/resources/db/migration/` (Flyway's default location). The
**infra container owns the schema** — `schedulerPostgresModule { runMigrations = true }`
runs `Flyway.migrate()` at startup, and `:standalone-runner` sets that flag.

User-apps bind `runMigrations = false` and `failFastOnSchemaMismatch = true` (the
default). At boot the module calls `Flyway.info().pending()`; non-empty pending list →
`error()` with "Run scheduler-infra first to apply migrations". This catches the deploy
ordering bug where someone deploys a new user-app before bumping the infra container.

### Adding a migration

1. **Pick the next version**: look at the highest `V<N>__` filename. Use a Flyway-style
   monotonic integer. Don't reuse / rename existing ones — Flyway hashes the script
   contents and refuses to run if a previously-applied migration changed.
2. **Name it descriptively**: `V5__add_job_archived_at.sql`, not `V5__changes.sql`.
3. **Author the SQL** for forward-compat **and** backward-compat. Migrations are
   applied by infra before user-app rolls. New columns either need a default or must be
   NULLABLE so old user-app code (which doesn't know about them) still inserts. Don't
   `DROP COLUMN` in the same release that stops writing to it — give one release of
   grace.
4. **Touch the matching `Table` object** in `infrastructure/tables/` and any
   `*RepositoryImpl` that needs to read the new column. Domain models in
   `domain/models/` only get the new field if it's exposed across module boundaries.
5. **Run the integration tests** locally with Testcontainers — they always apply all
   migrations on a fresh DB, so this is your smoke test that nothing's broken.

## How to extend

### Adding a new repository

1. Drop the interface in `domain/repositories/`.
2. Drop the Exposed impl in `infrastructure/repositories/`.
3. Wire it into `SchedulerPostgresModule.kt` via `single<MyRepository> { MyRepositoryImpl(get()) }`.
4. If it's used outside this module (i.e. by an engine or dashboard module), add it to
   `StorageProvider` interface + `PostgresStorageProvider` so the aggregator stays
   complete.
5. Integration test next to the others — e.g. start a Testcontainers PG, apply
   migrations, exercise the repo through a `suspendTransaction`.

### Adding a column to an existing table

See "Adding a migration" above. Plus:

- Update the matching `Table` object (`tables/JobTable.kt`, …).
- Update the model row (`domain/models/Job.kt`, …) **only if** the field is needed
  outside this module. Otherwise keep it internal to the repo and the mapper.
- Update any `INSERT` / `UPDATE` site in `JobRepositoryImpl` etc. — Exposed won't
  complain about a missing column at compile time.

## Quirks worth knowing

- **`idempotency_log` has no FK to `job`** — deliberate (DESIGN.md 18.4). Handler-side
  dedup retention (default 30d) is often longer than job retention (7d for succeeded).
  We don't want a stale-but-relevant idempotency entry to vanish when the job row gets
  retention-deleted.

- **`PostgresEventBus` opens its own dedicated JDBC connection** (not from the Hikari
  pool) because `LISTEN` holds the session long-term. Same reason as `LeaderElection`
  in `:engine-infra`. Don't try to consolidate — the pool would either reclaim the
  connection (silently dropping the LISTEN) or leak a slot.

- **`DefaultScheduler.enqueue` runs in a single transaction.** Insert into `job` +
  insert into `outbox` + insert into `job_event(ENQUEUED)` all commit together. If any
  step fails, nothing's published and the caller sees the exception. This is the
  outbox pattern (DESIGN.md 4.1) — don't add a "publish straight to Rabbit" shortcut.

- **CAS guards on `(id, version)` everywhere.** Every UPDATE in `JobRepositoryImpl`
  filters on the expected `version` and bumps it. A returned `0` row count means
  someone else moved the row; callers in `:engine-worker` log WARN and don't retry —
  that's the system telling us another actor won.

- **`payload_json` and `context_json` are JSONB.** PgJDBC needs `stringtype=unspecified`
  on the DataSource (covered above) for the implicit cast to work on INSERT.

- **`@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)`** at the top of any file that
  references `kotlin.uuid.Uuid`. Exposed 1.x's `uuid()` already returns this type, but
  the Kotlin compiler still gates it as experimental. Without the opt-in you get a
  warning soup.

- **`now()` in SQL vs `Clock.System.now()` in Kotlin.** Most timestamps are
  authored by the DB (`DEFAULT now()` or `now()` in UPDATE). Mixing in Kotlin clock
  reads opens the door to clock skew across infra container and user-app worker
  nodes — avoid unless you're testing.

- **There is no `Database` singleton.** The Koin graph binds one `Database` from the
  injected `DataSource`. `suspendTransaction` reads it from coroutine context. Don't
  call `Database.connect(...)` anywhere outside `SchedulerPostgresModule`.
