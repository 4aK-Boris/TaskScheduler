@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package cs.trade.scheduler.dashboard.server.domain.usecases

import cs.trade.scheduler.core.backend.Scheduler
import cs.trade.scheduler.core.backend.usecases.BaseUseCase
import cs.trade.scheduler.core.backend.usecases.runCatchingWithLogging
import cs.trade.scheduler.shared.RetryResult
import org.koin.core.annotation.Single
import kotlin.uuid.Uuid

/**
 * Counterpart to [CancelJobUseCase] for MANUAL_RETRY (DESIGN.md 18.6). Single caller
 * of [Scheduler.retry] from `:dashboard-server`. Returns [RetryResult] so the route
 * can pick the right HTTP status:
 *  - [RetryResult.RETRIED] → 200
 *  - [RetryResult.NOT_FAILED] → 409 Conflict (job is in a state where retry doesn't apply)
 *  - [RetryResult.CONFLICT] → 409 Conflict (CAS race)
 *  - [RetryResult.NOT_FOUND] → 404
 */
@Single
public class RetryJobUseCase(
    private val scheduler: Scheduler,
) : BaseUseCase() {

    public suspend operator fun invoke(jobId: Uuid, by: String?): Result<RetryResult> =
        runCatchingWithLogging {
            scheduler.retry(jobId, by)
        }
}
