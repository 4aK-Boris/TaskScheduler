package cs.trade.scheduler.dashboard.web.domain.usecases

import cs.trade.scheduler.dashboard.web.domain.repositories.JobsRepository
import cs.trade.scheduler.shared.RerouteResult

public class RerouteJobUseCase(
    private val repository: JobsRepository,
) {
    public suspend operator fun invoke(
        jobId: String,
        targetNode: String?,
        targetTag: String?,
        by: String? = null,
    ): Result<RerouteResult> = runCatching {
        repository.reroute(jobId, targetNode, targetTag, by)
    }
}
