@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package cs.trade.scheduler.dashboard.server.domain.usecases

import cs.trade.scheduler.core.backend.Scheduler
import cs.trade.scheduler.core.backend.usecases.BaseUseCase
import cs.trade.scheduler.core.backend.usecases.runCatchingWithLogging
import cs.trade.scheduler.shared.CancelResult
import org.koin.core.annotation.Single
import kotlin.uuid.Uuid

/**
 * Per the "1 function repo ↔ 1 UseCase" rule (DESIGN.md 3.3), this is the only caller of
 * [Scheduler.cancel] from `:dashboard-server`. The `by` argument flows from auth — the
 * actor extractor in `SchedulerDashboardConfig.auth` produces it.
 *
 * Returns [CancelResult] so the route can pick the right HTTP status:
 *  - [CancelResult.CANCELLED] / [CancelResult.CANCEL_REQUESTED] → 200
 *  - [CancelResult.ALREADY_TERMINAL] → 409 Conflict
 *  - [CancelResult.NOT_FOUND] → 404
 */
@Single
public class CancelJobUseCase(
    private val scheduler: Scheduler,
) : BaseUseCase() {

    public suspend operator fun invoke(jobId: Uuid, by: String?): Result<CancelResult> =
        runCatchingWithLogging {
            scheduler.cancel(jobId, by)
        }
}
