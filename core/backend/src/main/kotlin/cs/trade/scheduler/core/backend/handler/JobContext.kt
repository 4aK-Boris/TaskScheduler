package cs.trade.scheduler.core.backend.handler

import kotlin.time.Instant
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * Per-execution context passed to [JobHandler.execute]. See DESIGN.md section 12.4.
 *
 * `jobId` is the stable idempotency key across all attempts of one job
 * (retry, recovery, redelivery — it never changes). See DESIGN.md section 17.2.
 */
@OptIn(ExperimentalUuidApi::class)
public interface JobContext {
    public val jobId: Uuid
    public val attempt: Int               // 1-based: first attempt = 1
    public val queue: String
    public val enqueuedAt: Instant
    public val maxAttempts: Int
    public val parentJobIds: List<Uuid>

    /**
     * Report job progress. Throttled to at most once per second internally — calling more
     * often is safe but only the latest within each second is persisted. See DESIGN.md 22.3.
     */
    public suspend fun updateProgress(progress: Float, msg: String? = null)

    /**
     * `true` once someone has called [cs.trade.scheduler.core.backend.Scheduler.cancel] on
     * this job. Polled by handlers in long-running loops:
     * ```
     * if (ctx.isCancellationRequested()) throw JobCancellationException()
     * ```
     * Returning normally after cancel is also valid — it transitions to SUCCEEDED, not
     * CANCELLED, but the work was completed so that's the correct end state. Throwing
     * [cs.trade.scheduler.core.backend.handler.JobCancellationException] is the way to
     * mark the job as CANCELLED.
     */
    public suspend fun isCancellationRequested(): Boolean
}
