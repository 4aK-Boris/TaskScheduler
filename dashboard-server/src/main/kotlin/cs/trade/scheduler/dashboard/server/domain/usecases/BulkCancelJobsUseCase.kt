@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package cs.trade.scheduler.dashboard.server.domain.usecases

import cs.trade.scheduler.core.backend.Scheduler
import cs.trade.scheduler.core.backend.usecases.BaseUseCase
import cs.trade.scheduler.core.backend.usecases.runCatchingWithLogging
import cs.trade.scheduler.shared.CancelResult
import cs.trade.scheduler.shared.dto.BulkActionResponse
import org.koin.core.annotation.Single
import kotlin.uuid.Uuid

@Single
public class BulkCancelJobsUseCase(
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
                val result = scheduler.cancel(parsed, by)
                counts.merge(result.name, 1, Int::plus)
                // Both CANCELLED and CANCEL_REQUESTED are operator-success outcomes —
                // the cancel was accepted, even if the handler still has to acknowledge.
                if (result == CancelResult.CANCELLED || result == CancelResult.CANCEL_REQUESTED) ok++
            }
            BulkActionResponse(total = ids.size, ok = ok, byOutcome = counts)
        }
}
