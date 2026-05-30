@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package cs.trade.scheduler.engine.worker.domain.usecases

import cs.trade.scheduler.core.backend.events.EventBus
import cs.trade.scheduler.core.backend.usecases.BaseUseCase
import cs.trade.scheduler.core.backend.usecases.runCatchingWithLogging
import cs.trade.scheduler.shared.events.WebSocketEvent
import cs.trade.scheduler.storage.postgres.domain.repositories.JobRepository
import kotlin.time.Clock
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * Persists a handler-reported progress sample and emits a [WebSocketEvent.JobProgress]
 * so the dashboard's JobList / JobDetail can update without a full poll.
 *
 * **Throttling lives in the caller**, not here — `JobContextImpl` keeps a per-invocation
 * `lastReportedMillis` and short-circuits before we're invoked. That's intentional: a
 * use-case is the wrong place for "drop this call silently", and a global rate-limit
 * map would need eviction / coupling to job completion. Per-invocation state in the ctx
 * is bounded by the in-flight count and dies with the handler frame.
 *
 * State-scoped to PROCESSING (see [JobRepository.setProgress]) — late reports after a
 * terminal transition silently no-op.
 */
public class ReportProgressUseCase(
    private val jobs: JobRepository,
    private val eventBus: EventBus,
    private val propagateRollup: PropagateRollupProgressUseCase,
) : BaseUseCase() {

    public suspend operator fun invoke(
        jobId: Uuid,
        progress: Float,
        msg: String?,
        succeeded: Long? = null,
        failed: Long? = null,
        total: Long? = null,
    ): Result<Boolean> = runCatchingWithLogging {
        val now: Instant = Clock.System.now()
        val updated = jobs.setProgress(jobId, progress, msg, now, succeeded, failed, total)
        if (updated) {
            eventBus.publish(
                WebSocketEvent.JobProgress(
                    id = jobId.toString(),
                    progress = progress.coerceIn(0f, 1f),
                    msg = msg,
                    at = now,
                    succeeded = succeeded,
                    failed = failed,
                    total = total,
                ),
            )
            // Variant 3: walk upward through rollup parents. Errors here are swallowed
            // by the propagation use-case — bad rollup math must not fail the handler.
            propagateRollup(jobId)
        }
        updated
    }
}
