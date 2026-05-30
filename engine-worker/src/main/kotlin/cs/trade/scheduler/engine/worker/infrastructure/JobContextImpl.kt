@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package cs.trade.scheduler.engine.worker.infrastructure

import cs.trade.scheduler.core.backend.handler.JobContext
import cs.trade.scheduler.core.backend.handler.ProgressBar
import cs.trade.scheduler.engine.worker.domain.usecases.ReportProgressUseCase
import cs.trade.scheduler.storage.postgres.domain.repositories.JobRepository
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * Per-handler-invocation context.
 *
 * [updateProgress] is throttled to one DB write per [PROGRESS_MIN_INTERVAL_MS] (1s).
 * The "last reported" timestamp lives on the instance itself: one JobContextImpl per
 * handler call, so the rate-limit state dies with the frame — no global map, no
 * eviction headaches. Callers that exceed the throttle aren't told their write was
 * dropped (no point) — the next eligible call lands fresh data.
 *
 * [isCancellationRequested] reads `cancel_requested_at` from the job row. One DB round
 * trip per call — handlers should poll in their natural loop cadence, not in tight
 * loops. We don't cache because a stale `false` answer would defeat the whole point
 * of cooperative cancel.
 *
 * `parentJobIds` stays empty until the DAG `enqueueAfter` / `chain` path is wired
 * (DESIGN.md 8.3 / 8.4).
 */
public class JobContextImpl(
    override val jobId: Uuid,
    override val attempt: Int,
    override val queue: String,
    override val enqueuedAt: Instant,
    override val maxAttempts: Int,
    override val parentJobIds: List<Uuid> = emptyList(),
    private val jobs: JobRepository,
    private val reportProgress: ReportProgressUseCase,
) : JobContext {

    @Volatile private var lastReportedMillis: Long = 0L

    override suspend fun updateProgress(progress: Float, msg: String?) {
        report(progress, msg, succeeded = null, failed = null, total = null, force = false)
    }

    override fun progressBar(total: Long): ProgressBar =
        ProgressBarImpl(total = total, reporter = ::report)

    /**
     * Single funnel for both [updateProgress] and the counting [ProgressBar]. Applies the
     * shared per-invocation throttle (one DB write per [PROGRESS_MIN_INTERVAL_MS]) unless
     * [force] is set — the bar forces the completing sample through so it never sticks just
     * short of 100%. A forced write still advances [lastReportedMillis], so an immediately
     * following throttled call is correctly suppressed.
     */
    private suspend fun report(
        progress: Float,
        msg: String?,
        succeeded: Long?,
        failed: Long?,
        total: Long?,
        force: Boolean,
    ) {
        val now = System.currentTimeMillis()
        if (!force && now - lastReportedMillis < PROGRESS_MIN_INTERVAL_MS) return
        lastReportedMillis = now
        reportProgress(jobId, progress, msg, succeeded, failed, total)
    }

    override suspend fun isCancellationRequested(): Boolean =
        jobs.findById(jobId)?.cancelRequestedAt != null

    public companion object {
        public const val PROGRESS_MIN_INTERVAL_MS: Long = 1_000L
    }
}
