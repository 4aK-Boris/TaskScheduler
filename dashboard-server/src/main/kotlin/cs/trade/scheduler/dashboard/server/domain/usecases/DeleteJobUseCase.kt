@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package cs.trade.scheduler.dashboard.server.domain.usecases

import cs.trade.scheduler.core.backend.Scheduler
import cs.trade.scheduler.core.backend.usecases.BaseUseCase
import cs.trade.scheduler.core.backend.usecases.runCatchingWithLogging
import cs.trade.scheduler.shared.DeleteResult
import org.koin.core.annotation.Single
import kotlin.uuid.Uuid

/**
 * Counterpart to [CancelJobUseCase] / [RetryJobUseCase] for MANUAL_DELETE
 * (DESIGN.md 18.6). Single caller of [Scheduler.delete] from `:dashboard-server`.
 * Returns [DeleteResult] so the route can pick the right HTTP status:
 *  - [DeleteResult.DELETED] → 200
 *  - [DeleteResult.NOT_TERMINAL] → 409 Conflict (operator must cancel first)
 *  - [DeleteResult.NOT_FOUND] → 404
 */
@Single
public class DeleteJobUseCase(
    private val scheduler: Scheduler,
) : BaseUseCase() {

    public suspend operator fun invoke(jobId: Uuid, by: String?): Result<DeleteResult> =
        runCatchingWithLogging {
            scheduler.delete(jobId, by)
        }
}
