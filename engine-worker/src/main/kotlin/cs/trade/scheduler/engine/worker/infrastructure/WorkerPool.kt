@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package cs.trade.scheduler.engine.worker.infrastructure

import cs.trade.scheduler.core.backend.SchedulerCoreConfig
import cs.trade.scheduler.core.backend.context.ContextRestore
import cs.trade.scheduler.core.backend.context.OtelContextElement
import cs.trade.scheduler.shared.functionref.FunctionRefPayload
import cs.trade.scheduler.core.backend.handler.Job
import cs.trade.scheduler.core.backend.handler.JobCancellationException
import cs.trade.scheduler.core.backend.handler.JobHandler
import cs.trade.scheduler.engine.worker.domain.usecases.DeferPausedJobUseCase
import cs.trade.scheduler.engine.worker.domain.usecases.FinalizeJobUseCase
import cs.trade.scheduler.engine.worker.domain.usecases.ReportProgressUseCase
import cs.trade.scheduler.engine.worker.domain.usecases.ScheduleRetryUseCase
import cs.trade.scheduler.engine.worker.infrastructure.loops.HeartbeatLoop
import cs.trade.scheduler.engine.worker.infrastructure.loops.WorkerRegistryLoop
import cs.trade.scheduler.engine.worker.infrastructure.metrics.JobMetrics
import cs.trade.scheduler.engine.worker.infrastructure.metrics.JobOutcome
import cs.trade.scheduler.shared.JobState
import io.opentelemetry.api.trace.SpanKind
import io.opentelemetry.api.trace.StatusCode
import io.opentelemetry.api.trace.Tracer
import io.opentelemetry.context.Context
import cs.trade.scheduler.storage.postgres.domain.models.Job as JobRow
import cs.trade.scheduler.storage.postgres.domain.repositories.JobRepository
import cs.trade.scheduler.storage.postgres.domain.repositories.JobTypePauseRepository
import cs.trade.scheduler.storage.postgres.domain.repositories.WorkerRepository
import cs.trade.scheduler.storage.postgres.infrastructure.events.JobCancelListener
import cs.trade.scheduler.transport.rabbit.domain.ConsumerHandle
import cs.trade.scheduler.transport.rabbit.domain.JobTransport
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap
import kotlinx.serialization.SerializationException
import kotlinx.serialization.serializer
import org.slf4j.LoggerFactory
import org.slf4j.MDC
import kotlin.reflect.full.starProjectedType
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource
import kotlin.uuid.Uuid

/**
 * Worker-side orchestrator. On [start]:
 *  - for each queue declared in [SchedulerWorkerConfig.queues], opens a Rabbit consumer
 *    via [JobTransport.consume] with the configured prefetch,
 *  - the per-delivery handler does pickup → decode → dispatch → outcome.
 *
 * **Outcome routing:**
 *  - success → `markSucceeded`
 *  - exception, with `attempts < maxAttempts` AND a retry policy → [ScheduleRetryUseCase]
 *    (markForRetry + outbox row with `delay_ms = backoff`)
 *  - exception, no more attempts left OR no retry policy → `markFailed` + the user's
 *    `JobHandler.onFinalFailure` hook (any exception thrown by that hook is swallowed
 *    with a log — we never let cleanup callbacks crash the consumer)
 *
 * **Cancellation:** before invoking the handler, the row is re-checked for
 * `cancel_requested_at`; if set, we skip the handler and `markCancelled` directly.
 * A handler that opts into cooperative cancel throws
 * [JobCancellationException], which we treat as a terminal CANCELLED (no retry,
 * no `onFinalFailure` hook).
 *
 * **Pickup race:** Rabbit re-delivery (after a stale lock recovered by SafetyNetPoller)
 * can hit this worker even though another node still holds the row. [JobRepository.pickup]
 * returns null in that case and we silently ack — the row is owned elsewhere, no work
 * to do. See DESIGN.md 13.5 / 17.1.
 *
 * **Unknown payload_type:** if no handler is registered for the row's `payload_type`,
 * the job is marked FAILED so the dashboard surfaces the misconfiguration rather than
 * the message bouncing around. Phase 2 will introduce a "park" state (DESIGN.md 22.1).
 */
public class WorkerPool(
    private val transport: JobTransport,
    private val jobs: JobRepository,
    private val handlers: HandlerRegistry,
    private val scheduleRetry: ScheduleRetryUseCase,
    private val finalize: FinalizeJobUseCase,
    private val reportProgress: ReportProgressUseCase,
    private val deferPaused: DeferPausedJobUseCase,
    private val jobTypePauses: JobTypePauseRepository,
    private val heartbeatLoop: HeartbeatLoop,
    private val workerRegistryLoop: WorkerRegistryLoop,
    private val workerRegistry: WorkerRepository,
    private val inFlight: WorkerInFlightCounter,
    private val contextRestore: ContextRestore,
    private val metrics: JobMetrics,
    private val tracer: Tracer,
    private val workerConfig: SchedulerWorkerConfig,
    private val coreConfig: SchedulerCoreConfig,
    private val prefetchTuner: PrefetchTuner,
    private val functionRefRunner: FunctionRefRunner,
    private val circuitBreakers: CircuitBreakerRegistry,
    private val cancelListener: JobCancelListener,
    // Optional (nullable default) so the many tests that construct WorkerPool directly are
    // unaffected; schedulerWorkerModule always wires the real one. See DESIGN.md 22.9.
    private val schemaDriftCheck: SchemaDriftCheck? = null,
) {

    private val log = LoggerFactory.getLogger(javaClass)
    private val consumers = mutableListOf<ConsumerHandle>()
    private val mutex = Mutex()
    private var internalScope: CoroutineScope? = null

    /**
     * Jobs currently executing on THIS node, keyed by id (DESIGN.md 22.7). Populated for
     * the duration of `handler.execute` so the `job_cancel` listener can cancel the running
     * coroutine. [ActiveHandle.version] is the row version at pickup — `requestCancellation`
     * doesn't bump it, so it's still valid for the force-kill CAS finalize.
     */
    private val activeJobs = ConcurrentHashMap<Uuid, ActiveHandle>()

    private class ActiveHandle(val job: kotlinx.coroutines.Job, val version: Int)

    public suspend fun start() {
        mutex.withLock {
            check(consumers.isEmpty()) { "WorkerPool already started" }
            log.info(
                "WorkerPool starting — node={}, queues={}, knownPayloadTypes={}",
                workerConfig.nodeId, workerConfig.queues.map { it.name }, handlers.knownPayloadTypes,
            )
            // Schema-drift check (DESIGN.md 22.9): compare each handled payload type's current
            // serialization schema with the last-seen hash; WARN + alert on a change. Runs
            // before we consume; best-effort internally — won't block startup on a DB hiccup.
            schemaDriftCheck?.run()
            // Own the scope so user code doesn't need to manage it. Cancelled in stop().
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            internalScope = scope
            heartbeatLoop.start(scope)
            workerRegistryLoop.start(scope)
            // Push-based in-flight cancel (DESIGN.md 22.7): react to job_cancel NOTIFYs by
            // cancelling the running handler coroutine. onSignal is non-blocking — the grace
            // wait + force-kill happens in its own coroutine inside onCancelSignal.
            cancelListener.start(scope) { jobId -> onCancelSignal(jobId) }

            for (q in workerConfig.queues) {
                // Circuit breaker register BEFORE consume.start so the first pickup is
                // already gated. Per-node in-memory state; no cross-node sync.
                q.circuitBreaker?.let { circuitBreakers.register(q.name, it) }

                val handle = transport.consume(q.name, q.prefetch) { jobId ->
                    processOne(jobId, q)
                }
                consumers += handle
                // Adaptive prefetch: register the queue with the tuner and spin up a
                // tuner loop that periodically calls handle.setPrefetch(...). The tuner
                // sees execution-duration samples via `prefetchTuner.record(...)` in
                // processLockedInner's finally block; nothing more to wire on this side.
                val adaptive = q.adaptive
                if (adaptive != null) {
                    prefetchTuner.register(q.name, adaptive, initialPrefetch = q.prefetch)
                    scope.launch { tunerLoop(q, handle, adaptive.tuneInterval) }
                }
            }
        }
    }

    private suspend fun tunerLoop(queue: QueueConfig, handle: ConsumerHandle, interval: Duration) {
        // `delay` honours coroutine cancellation, so when stop() cancels internalScope
        // this loop unwinds at the next suspension. No explicit isActive check needed.
        while (true) {
            delay(interval.inWholeMilliseconds)
            val next = prefetchTuner.tune(queue.name) ?: continue
            runCatching { handle.setPrefetch(next) }
                .onFailure { log.warn("setPrefetch({}) on queue {} threw", next, queue.name, it) }
        }
    }

    /**
     * React to a `job_cancel` NOTIFY (DESIGN.md 22.7). Cancels the running handler
     * coroutine and waits up to [SchedulerWorkerConfig.cancelGracePeriod]:
     *  - **Cooperative handler** — unwinds at its next suspension point within the grace
     *    window; the delivery coroutine's `CancellationException` catch finalizes CANCELLED.
     *    Nothing more to do here.
     *  - **Non-cooperative handler** (CPU loop / blocking I/O that never yields) — still
     *    running past the grace window. We force the row terminal FAILED so the dashboard
     *    and DAG move on; the coroutine leaks until it eventually returns (documented
     *    trade-off). FAILED, not CANCELLED, signals "didn't stop cleanly".
     *
     * No-op if the job isn't running on this node (signal was for another worker, or the
     * handler already finished and deregistered).
     */
    private fun onCancelSignal(jobId: Uuid) {
        val handle = activeJobs[jobId] ?: return
        val scope = internalScope ?: return
        scope.launch {
            log.info("job_cancel signal for {} — cancelling handler (grace={})", jobId, workerConfig.cancelGracePeriod)
            handle.job.cancel(CancellationException("cancel requested via dashboard for job $jobId"))
            val finishedInGrace = withTimeoutOrNull(workerConfig.cancelGracePeriod.inWholeMilliseconds) {
                handle.job.join()
            }
            if (finishedInGrace == null) {
                val msg = "force-cancelled: handler did not respond within ${workerConfig.cancelGracePeriod}"
                log.warn("Job {} {} — marking FAILED; handler coroutine leaks until it yields", jobId, msg)
                runCatching {
                    finalize(jobId, handle.version, JobState.FAILED, errorMsg = msg)
                }.onFailure { log.warn("force-cancel finalize for {} threw", jobId, it) }
            }
        }
    }

    /**
     * Graceful shutdown. Cancels Rabbit consumers (no new pickups), then waits up to
     * [SchedulerWorkerConfig.shutdownTimeout] for in-flight `handler.execute` calls to
     * finish naturally. After the deadline the internal scope is forcibly cancelled and
     * any remaining handlers receive `CancellationException` at their next suspension
     * point — pure-CPU / blocking handlers won't honour it, but SafetyNetPoller will
     * recover their rows after [SchedulerWorkerConfig.lockDuration].
     */
    public suspend fun stop() {
        mutex.withLock {
            val scope = internalScope
            log.info(
                "WorkerPool stopping — cancelling {} consumer(s), grace={}",
                consumers.size, workerConfig.shutdownTimeout,
            )
            // Step 1: stop accepting new deliveries. cancel() on Rabbit consumers blocks
            // the broker from sending more messages to this node.
            consumers.forEach { runCatching { it.cancel() } }
            consumers.clear()

            // Step 2: wait for in-flight jobs to finish. We don't cancel the scope yet —
            // a hard cancel would abort handlers mid-execute, leaving rows PROCESSING
            // until SafetyNetPoller picks them up (slow recovery). Polling the scope's
            // children gives us a clean exit when they all complete naturally.
            if (scope != null) {
                val scopeJob = scope.coroutineContext.job
                val graceful = withTimeoutOrNull(workerConfig.shutdownTimeout.inWholeMilliseconds) {
                    // Wait for all children of the scope's job to complete. Note: this
                    // joins ONLY the existing children; new launches would be observed
                    // too if they happened, but we already cancelled the consumers.
                    while (scopeJob.children.any { it.isActive }) {
                        kotlinx.coroutines.delay(POLL_INTERVAL_MS)
                    }
                    true
                }
                if (graceful == null) {
                    log.warn(
                        "WorkerPool: {} in-flight job(s) exceeded shutdown grace {} — hard-cancelling. " +
                            "SafetyNetPoller will recover them after lockDuration={}.",
                        scopeJob.children.count { it.isActive },
                        workerConfig.shutdownTimeout,
                        workerConfig.lockDuration,
                    )
                }
                scope.cancel()
            }
            internalScope = null

            // Tell the dashboard this node is gone. Best-effort: a kill -9 won't get
            // here, but on graceful shutdown the dashboard rids of the stale row
            // immediately instead of waiting for retention to GC it.
            runCatching { workerRegistry.delete(workerConfig.nodeId) }
                .onFailure { log.warn("Failed to delete worker registry row for {}", workerConfig.nodeId, it) }
        }
    }

    private suspend fun processOne(jobId: Uuid, queue: QueueConfig) {
        val locked = jobs.pickup(
            jobId = jobId,
            nodeId = workerConfig.nodeId,
            lockDurationMillis = workerConfig.lockDuration.inWholeMilliseconds,
        )
        if (locked == null) {
            // Pre-restore log: MDC isn't populated yet (we don't have the row's queue/attempt
            // until pickup wins), but jobId/queue from the delivery are enough to correlate
            // with the safety-net poll that recovered this row elsewhere. Manual MDC scope
            // because we're outside the withContext(handlerCtx) that would inject it.
            MDC.put(MDC_JOB_ID, jobId.toString())
            MDC.put(MDC_JOB_QUEUE, queue.name)
            try {
                log.debug(
                    "pickup miss for {} on q={} — likely re-delivery of a row already owned by another node",
                    jobId, queue.name,
                )
            } finally {
                MDC.remove(MDC_JOB_ID)
                MDC.remove(MDC_JOB_QUEUE)
            }
            return
        }

        // Track per-queue in-flight from successful pickup through final outcome —
        // drives both worker.in_flight_count (sum) and worker.in_flight_by_queue (map)
        // in the registry table. finally ensures we decrement regardless of failure
        // mode below.
        inFlight.increment(queue.name)
        try {
            processLocked(jobId, locked)
        } finally {
            inFlight.decrement(queue.name)
        }
    }

    private suspend fun processLocked(jobId: Uuid, locked: JobRow) {
        // Stamp MDC for everything pre-restore. The coroutine-aware MDCContext only
        // attaches inside `withContext(handlerCtx)` below, so without this manual put
        // log lines on the pre-handler error paths (pause defer, no-handler, payload
        // decode failure, cancel-on-pickup) would emit with empty MDC. Cleared in
        // finally so a worker thread reused by a different delivery doesn't inherit
        // stale keys; restore() will re-set the same keys for the handler scope.
        MDC.put(MDC_JOB_ID, jobId.toString())
        MDC.put(MDC_JOB_QUEUE, locked.queue)
        MDC.put(MDC_JOB_ATTEMPT, locked.attempts.toString())
        try {
            processLockedInner(jobId, locked)
        } finally {
            MDC.remove(MDC_JOB_ID)
            MDC.remove(MDC_JOB_QUEUE)
            MDC.remove(MDC_JOB_ATTEMPT)
        }
    }

    private suspend fun processLockedInner(jobId: Uuid, locked: JobRow) {
        // Pause second-line check (DESIGN.md 22.1). The pause set may have been updated
        // after this row was already in Rabbit, so check now before doing any work.
        // Release-and-redeliver is preferred over rejecting the message because (a) the
        // attempts counter shouldn't be incremented for an operator-side pause, and
        // (b) the original outbox row is already gone, so the only way back into rotation
        // is a fresh outbox INSERT with delay.
        if (jobTypePauses.isPaused(locked.payloadType)) {
            log.info(
                "Job {} payload_type={} is paused — releasing lock + re-queueing in {}",
                jobId, locked.payloadType, PAUSE_REDELIVER_DELAY,
            )
            deferPaused(
                jobId = jobId,
                expectedVersion = locked.version,
                routingKey = routingKeyFor(locked),
                priority = locked.priority.value,
                backoff = PAUSE_REDELIVER_DELAY,
            )
            return
        }

        // Circuit-breaker gate (DESIGN.md 20.7). If this node's breaker is OPEN or has
        // an in-flight HALF_OPEN probe, release the lock + re-publish with a delay tied
        // to `openDuration` so the next attempt arrives right when we're ready to retry.
        // We DON'T trip on a refusal — the breaker tripped already and is intentionally
        // not letting work through.
        val cbConfig = workerConfig.queues.firstOrNull { it.name == locked.queue }?.circuitBreaker
        if (cbConfig != null && !circuitBreakers.tryAcquire(locked.queue)) {
            log.info(
                "Job {} on q={} skipped by circuit breaker (state={}) — releasing + re-queueing in {}",
                jobId, locked.queue, circuitBreakers.stateOf(locked.queue), cbConfig.openDuration,
            )
            deferPaused(
                jobId = jobId,
                expectedVersion = locked.version,
                routingKey = routingKeyFor(locked),
                priority = locked.priority.value,
                backoff = cbConfig.openDuration,
            )
            return
        }

        // Two dispatch shapes (DESIGN.md 21.1): sealed-class JobHandler vs function-ref
        // (payload_type == "function_ref"). For function-ref jobs there's no handler in
        // the registry and no @Serializable Job to decode — payload_json IS the
        // FunctionRefPayload itself, and execution goes through FunctionRefRunner. The
        // rest of the pipeline (pause, cancel, ctx restore, span, timeout, retry, finalize)
        // is shared verbatim.
        val isFunctionRef = locked.payloadType == FunctionRefPayload.FUNCTION_REF_PAYLOAD_TYPE
        val handler: JobHandler<Job>?
        val payload: Job?
        if (isFunctionRef) {
            handler = null
            payload = null
        } else {
            handler = handlers.findCasted(locked.payloadType)
            if (handler == null) {
                val msg = "No handler registered for payload_type=${locked.payloadType} " +
                    "(known: ${handlers.knownPayloadTypes})"
                log.error("Marking job {} FAILED: {}", jobId, msg)
                finalize(jobId, locked.version, JobState.FAILED, errorMsg = msg)
                return
            }
            payload = decodePayload(locked.payloadType, locked.payloadJson).getOrElse { cause ->
                // Decode failure at pickup is terminal — the stored bytes won't change on
                // retry (DESIGN.md 22.9). A schema mismatch (removed/renamed required field)
                // gets a message naming the mismatch; anything else falls back to the raw
                // cause. Either way: no retry, straight to FAILED.
                val schemaMsg = schemaErrorOf(cause)
                val msg = if (schemaMsg != null) {
                    "Schema error decoding payload (type=${locked.payloadType}): $schemaMsg"
                } else {
                    "Could not decode payload (type=${locked.payloadType}): ${cause.message ?: cause::class.simpleName}"
                }
                log.error("Marking job {} FAILED: {}", jobId, msg, cause)
                finalize(jobId, locked.version, JobState.FAILED, errorMsg = msg.take(MAX_ERROR_MSG_LEN))
                return
            }
        }

        val ctx = JobContextImpl(
            jobId = jobId,
            attempt = locked.attempts,
            queue = locked.queue,
            enqueuedAt = locked.createdAt,
            maxAttempts = locked.maxAttempts,
            jobs = jobs,
            reportProgress = reportProgress,
        )

        // Cancel may have been requested while this message sat in Rabbit between
        // requestCancellation (which set cancel_requested_at on the previous PROCESSING
        // attempt) and our pickup. Short-circuit the handler in that case.
        if (locked.cancelRequestedAt != null) {
            log.info("Job {} carried cancel_requested_at on pickup — skipping handler", jobId)
            finalize(jobId, locked.version, JobState.CANCELLED)
            return
        }

        // Restore MDC + OTel parent span captured at enqueue (DESIGN.md 22.11). Job-
        // intrinsic MDC keys are always added — useful even for jobs without a captured
        // context (cron-fired, system enqueues) so log lines from the handler still tell
        // you which job they came from.
        val restored = contextRestore.restore(
            contextJson = locked.contextJson,
            extraMdc = mapOf(
                "job_id" to jobId.toString(),
                "job_queue" to locked.queue,
                "job_attempt" to locked.attempts.toString(),
            ),
        )

        // Auto-span around handler.execute. Parent comes from the captured traceparent if
        // present, otherwise we root the trace here so the user still gets a trace they
        // can search by job_id. Span kind = CONSUMER (we picked work off Rabbit).
        // Span name = `<simplePayloadName>.execute` — low-cardinality (≤ number of
        // registered handlers) and reads naturally in Jaeger / Tempo trace UIs.
        val otelParent = restored.otelParent ?: Context.root()
        val spanName = "${locked.payloadType.substringAfterLast('.')}.execute"
        val span = tracer.spanBuilder(spanName)
            .setParent(otelParent)
            .setSpanKind(SpanKind.CONSUMER)
            .setAttribute(ATTR_JOB_ID, jobId.toString())
            .setAttribute(ATTR_JOB_QUEUE, locked.queue)
            .setAttribute(ATTR_JOB_PAYLOAD_TYPE, locked.payloadType)
            .setAttribute(ATTR_JOB_ATTEMPT, locked.attempts.toLong())
            .startSpan()
        // Make THIS span the current OTel context for the handler — overwrites the
        // parent-only OtelContextElement that restore() put in (same Key = replacement).
        //
        // Queue-level dispatcher override (DESIGN.md 13.3): if the QueueConfig declares
        // a `dispatcher`, it takes over inside `withContext(handlerCtx)` below so the
        // handler body runs on the user-chosen pool. Falls through to the consumer's
        // default Dispatchers.IO when null. Note: only the handler body switches — the
        // PG pickup / outbox writes / Rabbit ack stay on IO via their own `withContext`
        // wrappers in the repo layer.
        val queueConfig = workerConfig.queues.firstOrNull { it.name == locked.queue }
        val queueDispatcher = queueConfig?.dispatcher
        val handlerCtx = restored.coroutineContext +
            OtelContextElement(otelParent.with(span)) +
            (queueDispatcher ?: kotlin.coroutines.EmptyCoroutineContext)

        // Time only the user handler call — finalize / outbox writes are infra overhead
        // and would muddy the "how long does my job actually run" reading. `mark` snapshots
        // the monotonic clock; `elapsedNow()` is read once per outcome path, immediately
        // before we hand off to the (potentially slow) DB calls. See JobMetrics KDoc.
        // Job-specific timeout if set, otherwise default from config (DESIGN.md 8.2 / 22.4).
        // Caveat: withTimeout can only interrupt at coroutine suspension points. A pure-CPU
        // or blocking-I/O handler that doesn't yield won't be killed at the boundary —
        // it'll finish on its own time, but we'll still log it as timed-out from the
        // worker's perspective. Documented limit; handlers doing serious I/O should be
        // built on coroutine-aware libs anyway.
        val timeoutDuration: Duration = locked.timeoutSeconds?.toLong()?.seconds
            ?: coreConfig.defaultJobTimeout
        val mark = TimeSource.Monotonic.markNow()
        var executeDuration: Duration = Duration.ZERO
        var outcome: JobOutcome = JobOutcome.FAILED  // safe default if we somehow exit without setting it
        try {
            try {
                withContext(handlerCtx) {
                    // Register this handler coroutine so the job_cancel listener can cancel
                    // it mid-flight (DESIGN.md 22.7). The listener cancels THIS job; the
                    // outer delivery coroutine stays active and routes the CANCELLED outcome
                    // in the catch below. Removed in finally the instant the handler unwinds
                    // so a late signal finds nothing and a natural SUCCESS isn't clobbered.
                    activeJobs[jobId] = ActiveHandle(coroutineContext.job, locked.version)
                    try {
                        withTimeout(timeoutDuration) {
                            if (isFunctionRef) {
                                // Function-ref jobs don't take ctx in their user-facing method
                                // signature — the runner resolves target + method + args from the
                                // FunctionRefPayload and invokes via KFunction.callSuspend.
                                functionRefRunner.run(locked.payloadJson)
                            } else {
                                handler!!.execute(ctx, payload!!)
                            }
                        }
                    } finally {
                        activeJobs.remove(jobId)
                    }
                }
                executeDuration = mark.elapsedNow()
                outcome = JobOutcome.SUCCESS
                val ok = finalize(jobId, locked.version, JobState.SUCCEEDED).getOrThrow()
                if (!ok) log.warn("markSucceeded CAS conflict for job {} — concurrent mutation?", jobId)
            } catch (timeout: TimeoutCancellationException) {
                executeDuration = mark.elapsedNow()
                // Treat as a regular failure so retry policy / DAG cascade behaves the
                // same as any other exception. The default exception message includes the
                // budget ("Timed out waiting for 60000 ms"), so dashboards and logs
                // surface "why" at a glance without extra wrapping.
                log.warn("Job {} timed out after {} — running through failure path", jobId, timeoutDuration)
                outcome = handleFailure(locked, payload, handler, ctx, timeout)
                span.recordException(timeout)
                span.setStatus(StatusCode.ERROR, "timeout")
                span.setAttribute(ATTR_JOB_OUTCOME, outcome.tagValue)
                span.setAttribute("scheduler.job.timed_out", true)
            } catch (cancel: JobCancellationException) {
                executeDuration = mark.elapsedNow()
                outcome = JobOutcome.CANCELLED
                // Cancellation is a planned outcome, not an error — leave span status OK
                // but flag it as cancelled so traces are filterable.
                span.setAttribute(ATTR_JOB_OUTCOME, JobOutcome.CANCELLED.tagValue)
                log.info("Job {} honored cancel request via JobCancellationException", jobId, cancel)
                finalize(jobId, locked.version, JobState.CANCELLED, errorMsg = cancel.message)
            } catch (ce: CancellationException) {
                executeDuration = mark.elapsedNow()
                // Push-cancel (DESIGN.md 22.7): the job_cancel listener cancelled the handler
                // coroutine and it unwound at a suspension point. The delivery coroutine
                // itself is still active (only the child handler job was cancelled), so WE
                // own the terminal transition. If the delivery coroutine was ALSO cancelled
                // (worker shutdown tearing down the consumer scope), rethrow so structured
                // concurrency unwinds cleanly — the row stays PROCESSING and SafetyNetPoller
                // recovers it after lockDuration.
                if (!currentCoroutineContext().isActive) throw ce
                outcome = JobOutcome.CANCELLED
                span.setAttribute(ATTR_JOB_OUTCOME, JobOutcome.CANCELLED.tagValue)
                log.info("Job {} cancelled in-flight via job_cancel push — marking CANCELLED", jobId)
                // CAS-guarded on the pickup version. If the listener already force-FAILED
                // this row past the grace window, the version moved on and this no-ops.
                finalize(jobId, locked.version, JobState.CANCELLED, errorMsg = "cancelled in-flight")
            } catch (t: Throwable) {
                executeDuration = mark.elapsedNow()
                outcome = handleFailure(locked, payload, handler, ctx, t)
                span.recordException(t)
                span.setStatus(StatusCode.ERROR, t.message ?: t::class.simpleName ?: "error")
                span.setAttribute(ATTR_JOB_OUTCOME, outcome.tagValue)
            }
            if (outcome == JobOutcome.SUCCESS) {
                span.setAttribute(ATTR_JOB_OUTCOME, JobOutcome.SUCCESS.tagValue)
            }
        } finally {
            span.end()
            metrics.recordExecution(locked.queue, locked.payloadType, outcome, executeDuration)
            // Feed the adaptive prefetch tuner. We record on every outcome (success,
            // failure, timeout, cancelled) — what the tuner cares about is "how long is a
            // handler tying up an in-flight slot", and a slow failure ties up a slot just
            // as long as a slow success. The tuner is a no-op for queues without an
            // AdaptivePrefetch config (see PrefetchTuner.record's early return).
            prefetchTuner.record(locked.queue, executeDuration)
            // Feed the circuit breaker. CANCELLED counts as neither success nor failure
            // for the breaker's purposes (operator action, not downstream health). Only
            // genuine outcomes (SUCCESS, FAILED, RETRIED) flip the rolling window. RETRY
            // implies the handler threw; we count it as a failure since the breaker's
            // purpose is "downstream is sick", and a retry-able exception still means
            // the call failed once.
            when (outcome) {
                JobOutcome.SUCCESS -> circuitBreakers.record(locked.queue, success = true)
                JobOutcome.FAILED, JobOutcome.RETRIED -> circuitBreakers.record(locked.queue, success = false)
                JobOutcome.CANCELLED -> { /* operator-driven, not a health signal */ }
            }
            // Retry-rate metric (DESIGN.md 22.5). Fired only when the policy actually
            // scheduled a retry — not on terminal failures (those land in the
            // jobs.execution timer's outcome=FAILED tag). Lets Grafana show
            // `rate(scheduler_retry_total[5m])` to spot downstream flakiness.
            if (outcome == JobOutcome.RETRIED) {
                metrics.recordRetry(locked.queue, locked.payloadType)
            }
        }
    }

    private suspend fun handleFailure(
        locked: JobRow,
        payload: Job?,
        handler: JobHandler<Job>?,
        ctx: JobContextImpl,
        error: Throwable,
    ): JobOutcome {
        // Function-ref jobs (handler == null) don't have a per-handler retry policy or an
        // onFinalFailure hook — they fall through to the scheduler-level defaults. This
        // is the only behavioural difference between the two dispatch shapes; everything
        // else (retry, finalize, span recording) is shared.
        val policy = handler?.retryPolicy ?: coreConfig.defaultRetryPolicy
        val retriesLeft = policy != null && locked.attempts < locked.maxAttempts

        val errorMsg = error.message?.take(MAX_ERROR_MSG_LEN) ?: error::class.simpleName
        val errorStack = error.stackTraceToString().take(MAX_ERROR_STACK_LEN)

        // Schema-evolution guard (DESIGN.md 22.9): a payload that can't be deserialised
        // against the current code (removed/renamed required field, changed type) will
        // NEVER succeed on retry — the stored bytes don't change. Short-circuit to terminal
        // FAILED regardless of the retry policy, with a message that names the mismatch.
        // function-ref jobs surface here too: the runner decodes args at execute time, so a
        // stale arg schema throws SerializationException from inside handler.execute.
        schemaErrorOf(error)?.let { schemaMsg ->
            log.error("Job {} hit a payload schema error — terminal, no retry: {}", locked.id, schemaMsg, error)
            finalize(
                locked.id,
                locked.version,
                JobState.FAILED,
                errorMsg = "Schema error (payload incompatible with current code): $schemaMsg".take(MAX_ERROR_MSG_LEN),
                errorStack = errorStack,
            )
            if (handler != null && payload != null) {
                runCatching { handler.onFinalFailure(ctx, payload, error) }
                    .onFailure { log.error("onFinalFailure hook for job {} threw", locked.id, it) }
            }
            return JobOutcome.FAILED
        }

        if (!retriesLeft) {
            log.error(
                "Job {} failed terminally on attempt {}/{} (policy={})",
                locked.id, locked.attempts, locked.maxAttempts,
                policy?.let { it::class.simpleName } ?: "none",
                error,
            )
            finalize(locked.id, locked.version, JobState.FAILED, errorMsg = errorMsg, errorStack = errorStack)
            if (handler != null && payload != null) {
                runCatching { handler.onFinalFailure(ctx, payload, error) }
                    .onFailure { log.error("onFinalFailure hook for job {} threw", locked.id, it) }
            }
            return JobOutcome.FAILED
        }

        // policy is non-null here per the `retriesLeft` check above (smart-cast).
        val backoff = policy.nextBackoff(locked.attempts)
        log.warn(
            "Job {} attempt {}/{} failed — scheduling retry in {}ms",
            locked.id, locked.attempts, locked.maxAttempts, backoff.inWholeMilliseconds, error,
        )
        val routingKey = routingKeyFor(locked)
        val ok = scheduleRetry(
            jobId = locked.id,
            expectedVersion = locked.version,
            backoff = backoff,
            routingKey = routingKey,
            priority = locked.priority.value,
            errorMsg = errorMsg,
            errorStack = errorStack,
        ).getOrElse {
            log.error("ScheduleRetryUseCase threw for job {} — falling back to FAILED", locked.id, it)
            finalize(locked.id, locked.version, JobState.FAILED, errorMsg = errorMsg, errorStack = errorStack)
            return JobOutcome.FAILED
        }
        if (!ok) {
            log.warn("Retry CAS conflict for job {} — someone else moved the row, leaving it alone", locked.id)
        }
        return JobOutcome.RETRIED
    }

    private companion object {
        // Cap on what we persist into job_event — full stacks can be 10KB+ and bloat the
        // table without much extra signal on the UI. 8KB stack / 2KB msg is plenty.
        const val MAX_ERROR_MSG_LEN = 2_000
        const val MAX_ERROR_STACK_LEN = 8_000

        // Cap on cause-chain traversal in schemaErrorOf — defends against a pathological
        // self-referential cause cycle. 32 is far deeper than any real wrapping.
        const val MAX_CAUSE_DEPTH = 32

        // Re-deliver paused jobs after this long. Long enough that a brief pause doesn't
        // generate thrash, short enough that a typical unpause sees the row come back
        // within a minute or two.
        val PAUSE_REDELIVER_DELAY: Duration = 60.seconds

        // OTel span attribute keys. Prefixed with `scheduler.` to avoid collision with
        // OTel semantic conventions and keep them filterable in trace backends.
        const val ATTR_JOB_ID = "scheduler.job.id"
        const val ATTR_JOB_QUEUE = "scheduler.job.queue"
        const val ATTR_JOB_PAYLOAD_TYPE = "scheduler.job.payload_type"
        const val ATTR_JOB_ATTEMPT = "scheduler.job.attempt"
        const val ATTR_JOB_OUTCOME = "scheduler.job.outcome"

        // Poll interval for stop()'s drain loop. Short enough that shutdown is snappy
        // when handlers are quick to finish; not so short that we burn CPU on a tight
        // wait on the rare slow handler.
        const val POLL_INTERVAL_MS = 100L

        // MDC keys mirrored by ContextRestore.extraMdc so a log line emitted via the
        // pre-restore manual `MDC.put` path uses the SAME key names as a line emitted
        // via the kotlinx-coroutines-slf4j MDCContext path. Logback layouts referring
        // to %X{job_id} keep working across the boundary.
        const val MDC_JOB_ID = "job_id"
        const val MDC_JOB_QUEUE = "job_queue"
        const val MDC_JOB_ATTEMPT = "job_attempt"
    }

    private fun routingKeyFor(job: JobRow): String = when {
        job.targetNode != null -> "node.${job.targetNode}"
        job.targetTag != null -> "tag.${job.targetTag}"
        else -> job.queue
    }

    private fun decodePayload(payloadType: String, payloadJson: String): Result<Job> = runCatching {
        val kClass = Class.forName(payloadType).kotlin
        val serializer = serializer(kClass.starProjectedType)
        coreConfig.json.decodeFromString(serializer, payloadJson) as Job
    }

    /**
     * Walk the cause chain for a [SerializationException] — a payload schema mismatch
     * (DESIGN.md 22.9). Returns its message (or class name) when found, else null. Used to
     * classify a failure as non-retriable: re-running can't fix bytes that don't match the
     * current data class. The chain walk matters because the runner / kotlinx wrap the
     * original `SerializationException` as a `cause` under a higher-level exception.
     */
    private fun schemaErrorOf(t: Throwable): String? {
        var cur: Throwable? = t
        var guard = 0
        while (cur != null && guard < MAX_CAUSE_DEPTH) {
            if (cur is SerializationException) return cur.message ?: cur::class.simpleName ?: "deserialization error"
            cur = cur.cause
            guard++
        }
        return null
    }
}
