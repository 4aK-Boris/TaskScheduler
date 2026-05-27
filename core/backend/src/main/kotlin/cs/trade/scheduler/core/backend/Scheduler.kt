package cs.trade.scheduler.core.backend

import cs.trade.scheduler.core.backend.handler.Job
import kotlin.reflect.KFunction
import cs.trade.scheduler.shared.CancelResult
import cs.trade.scheduler.shared.DeleteResult
import cs.trade.scheduler.shared.RerouteResult
import cs.trade.scheduler.shared.RetryResult
import kotlin.time.Instant
import kotlin.time.Duration
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * Public scheduler facade. Pulled from Koin by user code after `startKoin { ... }`.
 *
 * See DESIGN.md sections 8 (public API) and 12 (Koin integration). Skeleton —
 * concrete impl wires together storage + transport + outbox + recurring + worker pool.
 */
@OptIn(ExperimentalUuidApi::class)
public interface Scheduler {

    public suspend fun start()
    public suspend fun stop(timeout: Duration)

    /** Immediate enqueue (DESIGN.md 8.2). */
    public suspend fun enqueue(job: Job, options: EnqueueOptions = EnqueueOptions()): Uuid

    /** Schedule for a specific future moment (DESIGN.md 8.2). */
    public suspend fun scheduleAt(
        job: Job,
        at: Instant,
        options: EnqueueOptions = EnqueueOptions(),
    ): Uuid

    /** Producer-side dedup via stable [key] (DESIGN.md 17.4). */
    public suspend fun enqueueOnce(
        key: String,
        job: Job,
        options: EnqueueOptions = EnqueueOptions(),
    ): Uuid

    /** Chain — each step depends on the previous (PROPAGATE_FAILURE). */
    public suspend fun chain(vararg jobs: Job): List<Uuid>

    /**
     * Barrier — enqueue [job] that waits for all [waitFor] parents to SUCCEED before becoming
     * ENQUEUED. Pair with [EnqueueOptions.onParentFailure] to customise propagation.
     */
    public suspend fun enqueueAfter(
        job: Job,
        waitFor: List<Uuid>,
        options: EnqueueOptions = EnqueueOptions(),
    ): Uuid

    /** Register a recurring (cron) definition. Idempotent — second call with same id updates. */
    public suspend fun recurring(definition: RecurringDefinition)

    /**
     * Cancel a job. Behaviour depends on the row's current state — see [CancelResult]:
     * non-terminal & non-PROCESSING flips to CANCELLED terminally; PROCESSING stamps
     * `cancel_requested_at` and relies on the handler to poll
     * [cs.trade.scheduler.core.backend.handler.JobContext.isCancellationRequested].
     *
     * @param by free-form identifier (user id, system name) recorded on the row for audit.
     */
    public suspend fun cancel(jobId: Uuid, by: String? = null): CancelResult

    /**
     * Operator-initiated MANUAL_RETRY (DESIGN.md 18.6). Only FAILED rows are retryable —
     * see [RetryResult] for the full outcome semantics. Resets `attempts` to 0 and
     * re-enqueues with a fresh outbox row (Rabbit picks it up the same way as a normal
     * enqueue). DAG dependents that already cascaded to FAILED on the original failure
     * are NOT auto-revived — operator must retry each branch they want re-run.
     *
     * @param by free-form identifier (user id, system name) recorded on the row for audit.
     */
    public suspend fun retry(jobId: Uuid, by: String? = null): RetryResult

    /**
     * Operator-initiated MANUAL_DELETE (DESIGN.md 18.6). Terminal-only — in-flight rows
     * must be cancelled first. The row passes through the configured
     * [cs.trade.scheduler.core.backend.archival.ArchivalSink] before DELETE so a forensic
     * record survives even when retention isn't due yet. `job_event`, `job_dependency`,
     * `outbox` children cascade-delete with the parent.
     *
     * @param by free-form identifier (user id, system name) emitted on the JobDeleted
     *           WS event for live audit. Not persisted past the delete itself (the row
     *           is gone) — for permanent audit configure an archival sink.
     */
    public suspend fun delete(jobId: Uuid, by: String? = null): DeleteResult

    /**
     * Operator-initiated RE-ROUTE (DESIGN.md 22.2). Redirects jobs stuck because their
     * `target_node` is offline / their `target_tag` has no consumers. Updates the row's
     * routing fields and inserts a fresh outbox row with the new routing key. The old
     * Rabbit message (if any) becomes a harmless re-delivery — `pickup` CAS guarantees
     * only one worker wins the row.
     *
     * Pass at least one of [targetNode] / [targetTag]; both null clears them (falls back
     * to the default queue). `node.*` wins if both are set, matching the outbox publisher's
     * routing-key preference.
     */
    public suspend fun reroute(
        jobId: Uuid,
        targetNode: String?,
        targetTag: String?,
        by: String? = null,
    ): RerouteResult

    /**
     * Function-ref enqueue primitive (DESIGN.md 21.2). The user-facing typed overloads
     * `enqueue(Receiver::method, a1, …, aN)` live as extension functions in
     * [cs.trade.scheduler.core.backend.functionref.FunctionRefEnqueueExtensions] and all
     * funnel into this single method — implementations only need to handle one shape.
     *
     * Implementations own the args serialisation: the configured
     * [cs.trade.scheduler.core.backend.SchedulerCoreConfig.json] is already in the
     * `Scheduler` impl, and threading it through the extension layer would force the
     * compile-time Koin plugin to validate a Koin lookup inside default-parameter
     * expressions (which it can't statically resolve in test modules — KOIN-D003).
     *
     * Multi-binding fail-fast: an implementation MUST verify that exactly one Koin binding
     * exists for the method's receiver class when [options.targetQualifier] is null, and
     * that a matching qualified binding exists otherwise. The exception thrown on miss
     * should be an [IllegalArgumentException] for symmetry with the args fail-fast in
     * [cs.trade.scheduler.core.backend.functionref.FunctionRefEnqueuer.build].
     */
    public suspend fun enqueueFunctionRef(
        method: KFunction<*>,
        args: List<Any?>,
        options: EnqueueOptions = EnqueueOptions(),
    ): Uuid

    /**
     * Lambda-capture entry point (DESIGN.md 21.9). User writes:
     *
     * ```
     * scheduler.enqueueLambda { mailer.send(123L, "welcome") }
     * ```
     *
     * The `scheduler-compiler-plugin` rewrites this call at compile time into a
     * [enqueueFunctionRef] invocation with the equivalent `KFunction` reference and
     * serialised args. WITHOUT the compiler plugin applied, calls to this function throw
     * at runtime — the bare stub here exists only so the call-site type-checks before
     * lowering.
     *
     * Constraints on the lambda (enforced at compile time by the plugin):
     *  - Single expression body — `{ receiver.method(arg1, arg2) }`. Conditionals,
     *    multi-statement, intermediate locals are rejected with a compile-time error.
     *  - Receiver must be a stable reference to a value in scope (typically a Koin-bound
     *    bean reachable from the outer function). The plugin emits a function-ref against
     *    the receiver's declared type, and the runtime resolves a binding from Koin.
     *  - All argument values must be `@Serializable` per the rules in
     *    [cs.trade.scheduler.core.backend.functionref.FunctionRefEnqueuer.build].
     */
    public suspend fun enqueueLambda(
        options: EnqueueOptions = EnqueueOptions(),
        @Suppress("UNUSED_PARAMETER") block: suspend () -> Unit,
    ): Uuid = throw IllegalStateException(
        "Scheduler.enqueueLambda { ... } must be lowered by the scheduler-compiler-plugin. " +
            "Add `id(\"cs.trade.scheduler.compiler\")` to your app's plugins block, or use the " +
            "explicit `enqueue(Receiver::method, args...)` form which works without the plugin.",
    )
}
