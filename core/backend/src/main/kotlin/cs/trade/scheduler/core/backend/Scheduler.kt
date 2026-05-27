package cs.trade.scheduler.core.backend

import cs.trade.scheduler.core.backend.handler.Job
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
}
