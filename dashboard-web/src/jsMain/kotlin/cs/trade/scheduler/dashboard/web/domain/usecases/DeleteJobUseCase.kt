package cs.trade.scheduler.dashboard.web.domain.usecases

import cs.trade.scheduler.dashboard.web.domain.repositories.JobsRepository
import cs.trade.scheduler.shared.DeleteResult

/** Wraps [JobsRepository.delete]. `by` flows from the auth principal (when wired). */
public class DeleteJobUseCase(
    private val repository: JobsRepository,
) {
    public suspend operator fun invoke(jobId: String, by: String? = null): Result<DeleteResult> =
        runCatching { repository.delete(jobId, by) }
}
