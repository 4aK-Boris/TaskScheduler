@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package cs.trade.scheduler.dashboard.server.domain.usecases

import cs.trade.scheduler.core.backend.Scheduler
import cs.trade.scheduler.core.backend.usecases.BaseUseCase
import cs.trade.scheduler.core.backend.usecases.runCatchingWithLogging
import cs.trade.scheduler.shared.RerouteResult
import org.koin.core.annotation.Single
import kotlin.uuid.Uuid

/**
 * Bridges the dashboard route to [Scheduler.reroute] (DESIGN.md 22.2). Same actor /
 * audit story as the other manual actions.
 */
@Single
public class RerouteJobUseCase(
    private val scheduler: Scheduler,
) : BaseUseCase() {

    public suspend operator fun invoke(
        jobId: Uuid,
        targetNode: String?,
        targetTag: String?,
        by: String?,
    ): Result<RerouteResult> = runCatchingWithLogging {
        scheduler.reroute(jobId, targetNode, targetTag, by)
    }
}
