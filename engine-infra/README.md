# engine-infra

Background loops that keep the scheduler alive: outbox publish, recurring fire, fast-forward,
safety-net recovery, retention cleanup. **Lives only in the `scheduler-infra` JVM**, never
in user-app workers — there are no `JobHandler` registrations here and no concept of
"executing a payload".

For the big picture (state machine, deployment topology, two-container model) see
[DESIGN.md](../DESIGN.md) sections 3 (modules), 7.1 / 7.3 / 7.5 (outbox / safety-net /
recurring flows), 11.5 (fast-forward), 14 (deployment), 18 (retention).

## What this module does

This is the operational heart of `scheduler-infra`. The five background loops in
`infrastructure/loops/` each own one cross-cutting concern:

| Loop | Tick | Responsibility |
|---|---|---|
| `OutboxPublisher`     | 100ms  | drain `outbox` rows into Rabbit, then mark `published_at` |
| `RecurringScheduler`  | 30s    | scan `recurring_job` for rows with `next_trigger_at <= now`, enqueue + bump `next_trigger_at` via `cron-utils` |
| `FastForwardTask`     | 30s    | promote `SCHEDULED` rows that fell inside the `fastForwardWindow` (default 24h) into `ENQUEUED` + outbox INSERT |
| `SafetyNetPoller`     | 30s    | recover PROCESSING rows where `locked_until < now` (worker crashed / GC paused too long) |
| `RetentionCleanup`    | 1h     | batch-DELETE terminal jobs / dead worker rows / old outbox / idempotency_log entries |

Each loop wraps a domain `*UseCase` from `domain/usecases/`. The use case is the **what**
(one batch of work, returns a `Result<T>`); the loop is the **when** (intervals, leader
gating, error swallowing). Splitting it that way keeps unit tests focused on the use case
and lets integration tests drive the loop without `Thread.sleep`.

All five loops are gated by `isLeader: () -> Boolean` passed to `start()`. The
`LeaderElection` in `infrastructure/leader/` provides that gate via PG advisory locks —
see [DESIGN.md 14.3](../DESIGN.md). Followers tick on schedule but do no work, so they're
ready to take over within one election interval (5s) of the leader stepping down. Even
without gating the loops are correctness-safe (every state mutation is CAS-guarded on
`version`); gating just avoids the wasted publish-then-CAS-loss Rabbit roundtrip.

## How it's deployed

Bundled into the `:standalone-runner` fat-jar. `Application.kt` (in `:standalone-runner`)
loads `schedulerInfraModule { ... }`, resolves each loop from Koin, and starts them on a
shared `SupervisorJob` scope. A single replica is the MVP target (DESIGN.md 14.3); the
leader-election plumbing is wired up so Phase 2 multi-replica is just a deploy change, not
a code change.

The Docker image (`docker/infra/Dockerfile`) bakes the runner jar. Health probes at
`/health/leader` and `/health/ready` (served from `:standalone-runner`) cover the loops'
liveness — there's no liveness endpoint inside this module on its own.

## What's in here

```
src/main/kotlin/cs/trade/scheduler/engine/infra/
  domain/
    usecases/
      PublishOutboxBatchUseCase.kt        — drain N outbox rows, publish, mark
      FireDueRecurringJobsUseCase.kt      — recurring → job + bump next_trigger_at
      FastForwardScheduledJobsUseCase.kt  — SCHEDULED ⇒ ENQUEUED for jobs <24h away
      RecoverOrphanedJobsUseCase.kt       — PROCESSING with stale lock ⇒ re-enqueue
      RetentionCleanupBatchUseCase.kt     — batch-DELETE old rows
  infrastructure/
    SchedulerInfraModule.kt               — Koin DSL: schedulerInfraModule { … }
    leader/
      LeaderElection.kt                   — pg_try_advisory_lock on a dedicated raw conn
    loops/
      OutboxPublisher.kt                  — tick wrapper around PublishOutboxBatchUseCase
      RecurringScheduler.kt               — tick wrapper around FireDueRecurringJobsUseCase
      FastForwardTask.kt                  — tick wrapper around FastForwardScheduledJobsUseCase
      SafetyNetPoller.kt                  — tick wrapper around RecoverOrphanedJobsUseCase
      RetentionCleanup.kt                 — tick wrapper around RetentionCleanupBatchUseCase
src/test/kotlin/.../
  OutboxPublisherIntegrationTest.kt       — Testcontainers PG + Rabbit happy path
  OutboxPublisherLeaderGateTest.kt        — isLeader=false ⇒ no publishes
  FastForwardIntegrationTest.kt
  RecurringIntegrationTest.kt
  RetentionIntegrationTest.kt
  SafetyNetIntegrationTest.kt
  LeaderElectionFailoverTest.kt           — leader dies ⇒ follower acquires within tick
```

## How to add a new infra loop

The recipe, in order:

1. **Drop a use case** in `domain/usecases/` that takes its repository deps in the
   constructor and returns `Result<SomeShape>` from a single suspend `invoke`. One batch
   of work per call. Errors become `Result.failure` — never throw across the boundary,
   the loop catches `onFailure` and logs.

2. **Drop a loop wrapper** in `infrastructure/loops/` next to the others. Copy
   `SafetyNetPoller` as a starting point — it's the smallest. The wrapper:
   - takes the use case + `SchedulerInfraConfig` (for interval) + any read-only
     repositories it samples for log lines (see `OutboxPublisher.outbox` for the backlog
     check pattern).
   - exposes `fun start(scope, isLeader = { true }): Job` returning a launched coroutine
     so the runner can `cancel()` it cleanly.
   - calls `useCase().onFailure { log.error(...) }` inside the `isActive` loop and never
     throws — a persistent DB outage spams the log but does not kill the loop.

3. **Wire it into `SchedulerInfraModule.kt`** via `singleOf(::YourUseCase)` and
   `singleOf(::YourLoop)`. If your loop needs a new tunable, add it to
   `SchedulerInfraConfig` with a sensible default (mirror what `outboxPollInterval`
   already does).

4. **Start it from `Application.kt`** with the same leader gate as everything else:
   `koin.get<YourLoop>().start(loopsScope, isLeader = isLeader)`. Order doesn't matter
   functionally — pick a placement that matches the existing reading order (outbox /
   safety-net / fast-forward / recurring / retention).

5. **Test with Testcontainers**. Copy the structure of `RetentionIntegrationTest` — it
   starts PG only (no Rabbit needed for retention) and verifies via direct SQL. Loops
   that publish need the Rabbit container too — see `OutboxPublisherIntegrationTest`.

## Quirks worth knowing

- **`isLeader` is a function, not a flag.** Loops re-read it every tick because the
  follower→leader transition happens out-of-band when the previous leader's PG session
  dies. Capturing the boolean once would freeze the gate.

- **Leader-conn bypass.** `LeaderElection` opens its session via `DriverManager`, not the
  Hikari pool, because `pg_try_advisory_lock` holds the lock for the **session** lifetime.
  Hikari would either return the connection (releasing the lock) or never reclaim it
  (silently leaking a pool slot). Don't "consolidate" this to use the pool.

- **Loops must not throw out of `start()`.** Every loop swallows `Throwable` from its use
  case and logs. Letting an exception propagate would kill the `SupervisorJob` child;
  while the SupervisorJob keeps siblings alive, your loop would silently stop ticking.
  Look at how `OutboxPublisher` wraps `publishBatch().onFailure { log.error(...) }`.

- **Outbox backpressure.** `OutboxPublisher` uses a bounded channel internally (capacity
  `batch × 2`) so the publish pipeline applies natural backpressure if Rabbit chokes —
  the loop won't burn CPU spinning on a stuck broker. See DESIGN.md section 20.

- **Retention is destructive.** `RetentionCleanupBatchUseCase` issues
  `DELETE … LIMIT batchSize` in batches. Schema has `ON DELETE CASCADE` on dependents
  (`job_event`, `job_dependency`, `outbox`), so the actual row count touched per tick is
  much larger than `cleanupBatchSize` — that's intentional but be aware when tuning.
  `idempotency_log` is **NOT** cascaded from `job` (DESIGN.md 18.4) — it has its own TTL
  because handler-side dedup may need a longer retention than job-history retention.

- **Time format.** All `Duration` values are `kotlin.time.Duration` (not java.time). When
  passing intervals to coroutines, convert with `.inWholeMilliseconds`.

- **No `start()` synchronisation.** Each loop's `start()` is called exactly once from
  `Application.kt`. They don't guard against double-start. If you need multi-start (rare)
  add a `Mutex` plus an `internalScope` field — see how `WorkerPool` does it in
  `:engine-worker`.

- **Restart-on-fail is single-replica's safety net.** The infra container is a single
  replica in MVP; a hard crash relies on k8s/docker-compose `restart: unless-stopped` to
  bring it back within ~5-10s. Jobs are durable in PG and Rabbit (delivery_mode=2), so
  in-flight work isn't lost. Don't add in-memory caches to these loops that would need a
  warm-up phase after restart.
