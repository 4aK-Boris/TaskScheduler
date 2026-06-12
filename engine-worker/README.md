# engine-worker

The worker side of the scheduler: Rabbit consumer pool, handler dispatch, retry, DAG
finalize, progress reporting, heartbeat. **Lives only in user-app JVMs**, never in the
`scheduler-infra` container — handler classes are user code and must not leak across the
deploy boundary.

For the big picture (state machine, deployment topology, two-container model) see
[DESIGN.md](../DESIGN.md) sections 3 (modules), 7.2 (worker pickup), 12 (Koin integration),
13 (worker pool & lifecycle), 16 (retry policy), 17 (idempotency), 22.11 (context
propagation).

## What this module does

When a user-app boots with `schedulerWorkerModule { … }`, this module:

1. **Opens one Rabbit consumer per declared queue** (`transport.consume(q.name, q.prefetch)`).
   Rabbit's prefetch is the natural backpressure knob: `prefetch >= concurrency` and the
   broker stops delivering once that many messages are unacked.
2. **On every delivery, runs the pickup → decode → dispatch → outcome pipeline** in
   `WorkerPool.processOne`:
   - CAS-pickup of the `job` row (atomic state transition to `PROCESSING` + `locked_by` set);
   - second-line `payload_type` pause check (DESIGN.md 22.1);
   - handler lookup via `HandlerRegistry` (built from `getAll<JobHandler<*>>()` plus `@JobType`);
   - kotlinx-serialization decode of `payload_json`;
   - restore captured MDC + OTel parent via `ContextRestore` (DESIGN.md 22.11);
   - `withTimeout(jobOrDefault) { handler.execute(ctx, payload) }`;
   - route the outcome (success / retry / final failure / cancel) through the right use case.
3. **Heartbeats the lock** every 30s via `HeartbeatLoop` — one `UPDATE … WHERE locked_by =
   :me AND state = 'PROCESSING'` extends `locked_until` for every in-flight job at once
   so DB load doesn't scale with concurrency.
4. **Stamps the worker row** every 30s via `WorkerRegistryLoop` so the dashboard's
   `/api/workers` knows we're alive.
5. **Drains gracefully on `stop()`** — two-phase: `ConsumerHandle.stopDeliveries()` stops
   NEW deliveries (channels and handler scopes stay alive), then the in-flight counter is
   polled up to `shutdownTimeout`; only after the drain do consumers get the full
   `cancel()` and the service loops stop. An idle worker stops in ~100ms; jobs that don't
   finish within the grace are hard-cancelled and recovered by `SafetyNetPoller`
   (in `:engine-infra`) after `lockDuration` (default 90s). The node's `worker` registry
   row is deleted at the end (after `WorkerRegistryLoop` is stopped, so it can't re-upsert).

## How user apps consume it

Pure Koin DSL — see `SchedulerWorkerModule.kt`:

```kotlin
startKoin {
    modules(
        AppModule().module,                         // user's Koin Annotations module
        schedulerCoreModule { nodeId = "app-1" },
        schedulerPostgresModule { dataSource = get(); runMigrations = false },
        schedulerRabbitModule { connectionFactory = get(); queues = listOf("default", "email") },
        schedulerWorkerModule {
            nodeId = "app-1"
            queue("default", concurrency = 10)
            queue("email",   concurrency = 20)
            queue("heavy",   concurrency = 2, prefetch = 4)
        },
    )
}
val pool = koin.get<WorkerPool>()
runBlocking { pool.start() }
// later: pool.stop()
```

Handler bindings use **two annotations** (DESIGN.md 12.3) — `@Single(binds = [JobHandler::class])`
is standard Koin, `@JobType(SomeJob::class)` is ours and tells `HandlerRegistry` which
`payload_type` strings route to which handler:

```kotlin
@Single(binds = [JobHandler::class])
@JobType(SendEmail::class)
class SendEmailHandler(private val mailer: Mailer) : JobHandler<SendEmail> {
    override suspend fun execute(ctx: JobContext, job: SendEmail) {
        mailer.send(job.userId, job.template)
    }
}
```

`HandlerRegistry` is built once at construction time from every `JobHandler<*>` Koin
knows about. Missing `@JobType` → constructor throws. Two handlers for the same
`payload_type` → constructor throws. Both are fail-fast so misconfiguration is caught at
boot, not at job-pickup time.

## Handler lifecycle

For each delivery:

1. `pickup(jobId, nodeId, lockDuration)` — null means another node owns the row
   (re-delivery race) → silently ack.
2. `inFlight.increment(queue)` — drives `worker.in_flight_count` and `…_by_queue` for the
   dashboard. Mirrored decrement in `finally`.
3. **Pause check** → `deferPaused()` → fresh outbox INSERT with delay, the row goes back
   into rotation in a minute. No attempts bump (operator-side pause is not a job failure).
4. **`@JobType` lookup** → no handler ⇒ `markFailed("No handler registered for …")` with
   the known set in the message. DLQ-style behaviour without an actual DLQ.
5. **`json.decodeFromString`** → SerializationException ⇒ `markFailed("Could not decode
   payload …")`. Non-retriable; a schema bug isn't going to fix itself on retry.
6. **`ContextRestore.restore`** — overlays captured MDC + OTel parent on top of the
   intrinsic keys (`job_id`, `job_queue`, `job_attempt`). The intrinsic keys are also
   set manually before this step so the pre-restore log lines (pause defer, no-handler,
   decode error, cancel-on-pickup) get them too.
7. **OTel auto-span** — `<simpleType>.execute`, kind=CONSUMER, parent = restored
   traceparent if any. The span is the OTel current context for the handler.
8. **`withTimeout(jobOrDefault) { handler.execute(ctx, payload) }`** — `TimeoutCancellationException`
   is routed through the same `handleFailure` path as any other exception (retry if
   policy allows, otherwise FAILED + `onFinalFailure` hook).
9. **`JobCancellationException`** — terminal CANCELLED, no retry, no `onFinalFailure`.
   Handler opts in by throwing this when its own cooperative-cancel check fires.

## What's in here

```
src/main/kotlin/cs/trade/scheduler/engine/worker/
  domain/
    models/
      WorkerLease.kt                  — domain value for pickup() result
    usecases/
      ScheduleRetryUseCase.kt         — markForRetry + outbox INSERT(delay_ms = backoff)
      FinalizeJobUseCase.kt           — terminal state transitions + DAG fan-out
      PropagateRollupProgressUseCase  — rollup parent updates when a leaf finishes
      ReportProgressUseCase           — throttled (1s) UPDATE + WS NOTIFY
      DeferPausedJobUseCase           — release lock + fresh outbox row with delay
  infrastructure/
    SchedulerWorkerModule.kt          — Koin DSL: schedulerWorkerModule { … }
    WorkerPool.kt                     — the orchestrator (start/stop, processOne)
    HandlerRegistry.kt                — payload_type → JobHandler<*>, built at boot
    JobContextImpl.kt                 — runtime JobContext exposed to handlers
    WorkerInFlightCounter.kt          — per-queue in-flight counter for the registry row
    loops/
      HeartbeatLoop.kt                — lock-extension UPDATE every heartbeatInterval
      WorkerRegistryLoop.kt           — upsert worker(node_id, last_heartbeat, …)
    metrics/
      JobMetrics.kt                   — Noop / Micrometer SAM for histogram + counters
      MicrometerJobMetrics.kt         — production impl, opt-in via Koin override
      WorkerMetricsBinder.kt          — gauges (in-flight-by-queue, alive)
      JobOutcome.kt                   — SUCCESS / RETRIED / FAILED / CANCELLED tag value
```

## How to extend

### Adding a new handler (user-app side)

`@Single(binds = [JobHandler::class])` + `@JobType(MyJob::class)`. The Koin Annotations
processor picks it up automatically because of `binds = [JobHandler::class]`. No registry
edits needed.

### Adding a new outcome path (rare)

For example, a new "PARKED" state for poison messages. The shape to follow:

1. Add a new `JobState` enum value in `:core:shared`.
2. Add the corresponding `markParked` to `JobRepository` (storage-postgres).
3. Drop a new use case in `domain/usecases/` (e.g. `ParkJobUseCase`) that bumps the
   state, emits a `job_event`, and skips the outbox.
4. Branch in `WorkerPool.processLocked` (`handleFailure` if it's an exception path, or
   a new branch before the handler call if it's a static decision).
5. Add a `JobOutcome` value in `metrics/JobOutcome.kt` so the Prometheus histogram tag
   stays exhaustive.
6. Integration test that drives the state transition with Testcontainers — copy
   `RetryIntegrationTest` as a template.

### Adding a new per-queue knob

Edit `QueueConfig` in `SchedulerWorkerModule.kt`. The `queue(…)` DSL forwards to it.
Anything you add becomes opt-in per queue — keep the default sane (defaults are
applied in the DSL function). Don't add knobs to global `SchedulerWorkerConfig` unless
they really are global.

## Quirks worth knowing

- **MDC straddles the restore boundary.** Pre-restore log lines (pickup miss, pause
  defer, no-handler, payload decode, cancel-on-pickup) use **thread-local MDC** stamped
  manually in `WorkerPool`. Inside `withContext(handlerCtx)` the same keys come from
  `kotlinx-coroutines-slf4j` `MDCContext` (set by `ContextRestore`). Same key names
  (`job_id`, `job_queue`, `job_attempt`) so Logback `%X{job_id}` works across both.

- **`withTimeout` only interrupts at suspension points.** A pure-CPU handler that
  doesn't yield won't be killed when its timeout expires — it'll just keep running. The
  worker still logs the timeout and routes through the failure path; `SafetyNetPoller`
  cleans up after `lockDuration` if the row is still PROCESSING. Document timeouts as
  a guideline for cooperative handlers, not a hard kill.

- **At-least-once execution is the contract.** Network partitions, GC pauses longer
  than `lockDuration`, and worker crashes after side-effect-but-before-COMMIT can all
  cause a job to run twice. See `IdempotencyStore` (`:storage-postgres`) and
  DESIGN.md 17 for the dedup patterns we expose.

- **Two CAS layers protect every state transition.** Pickup CAS on
  `(state, locked_by)` so two workers can't both claim a row. Finalize CAS on
  `(version)` so a replay can't overwrite the freshly-set terminal state. `WorkerPool`
  logs WARN on a CAS miss but doesn't retry — that's the system telling us another
  actor moved the row and we should stop.

- **`ack` semantics.** Rabbit consumers are `autoAck = false`. The transport-rabbit
  consumer ack's only after the suspend handler returns (success path or recovered
  failure). A hard crash before ack ⇒ Rabbit redelivers ⇒ pickup CAS rejects ⇒ we
  silently ack the duplicate. The shape works without our cooperation; just don't
  ack manually from a handler.

- **Don't catch `CancellationException` in handlers.** It's how cooperative shutdown
  works. Wrap your own try/catch around `Exception` if you must, but let
  `CancellationException` propagate.

- **`onFinalFailure` is best-effort.** If the hook itself throws, the worker logs and
  swallows it. The job stays FAILED — we don't roll back the state transition. Treat
  the hook as a fire-and-forget notification, not a transactional step.

- **`schedulerWorkerModule` is not required.** A user-app that only enqueues (no
  consumers) skips this module entirely. See DESIGN.md 13.7.

- **The `HandlerRegistry` exposes `find(payloadType)` as `JobHandler<*>?`** —
  `findCasted(...)` is the internal version that returns `JobHandler<Job>?` for
  WorkerPool. Don't expose `findCasted` publicly; the unchecked cast lives there
  because the on-the-wire payload erases generic type info.
