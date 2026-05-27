@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package cs.trade.scheduler.dashboard.server.domain.usecases

import cs.trade.scheduler.core.backend.Scheduler
import cs.trade.scheduler.core.backend.usecases.BaseUseCase
import cs.trade.scheduler.core.backend.usecases.runCatchingWithLogging
import cs.trade.scheduler.shared.RetryResult
import cs.trade.scheduler.shared.dto.BulkActionResponse
import org.koin.core.annotation.Single
import kotlin.uuid.Uuid

/**
 * Bulk wrapper for [Scheduler.retry]. Iterates sequentially — bulks are capped at
 * [cs.trade.scheduler.shared.dto.BulkIdsRequest.MAX_BATCH_SIZE] (100) so the per-item
 * tx overhead is bounded. Parallelism would hit the same job rows that the worker pool
 * is reading; sequential keeps it boring.
 *
 * Malformed UUIDs in the batch are counted as `INVALID_ID` in [BulkActionResponse.byOutcome]
 * — the operator can spot bad input without 400-ing the whole batch.
 */
@Single
public class BulkRetryJobsUseCase(
    private val scheduler: Scheduler,
) : BaseUseCase() {

    public suspend operator fun invoke(ids: List<String>, by: String?): Result<BulkActionResponse> =
        runCatchingWithLogging {
            val counts = mutableMapOf<String, Int>()
            var ok = 0
            for (raw in ids) {
                val parsed = runCatching { Uuid.parse(raw) }.getOrNull()
                if (parsed == null) {
                    counts.merge("INVALID_ID", 1, Int::plus)
                    continue
                }
                val result = scheduler.retry(parsed, by)
                counts.merge(result.name, 1, Int::plus)
                if (result == RetryResult.RETRIED) ok++
            }
            BulkActionResponse(total = ids.size, ok = ok, byOutcome = counts)
        }
}
