package cs.trade.scheduler.dashboard.web.domain.usecases

import cs.trade.scheduler.dashboard.web.domain.repositories.JobsRepository
import cs.trade.scheduler.shared.dto.JobDetail

/** Wraps [JobsRepository.detail]. `null` = 404 from the server. */
public class GetJobDetailUseCase(
    private val repository: JobsRepository,
) {
    public suspend operator fun invoke(jobId: String): Result<JobDetail?> = runCatching {
        repository.detail(jobId)
    }
}
