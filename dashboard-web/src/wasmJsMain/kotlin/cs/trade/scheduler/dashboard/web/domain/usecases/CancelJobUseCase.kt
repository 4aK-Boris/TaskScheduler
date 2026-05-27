package cs.trade.scheduler.dashboard.web.domain.usecases

import cs.trade.scheduler.dashboard.web.domain.repositories.JobsRepository
import cs.trade.scheduler.shared.CancelResult

/** Wraps [JobsRepository.cancel]. `by` flows from the auth principal (when wired). */
public class CancelJobUseCase(
    private val repository: JobsRepository,
) {
    public suspend operator fun invoke(jobId: String, by: String? = null): Result<CancelResult> =
        runCatching { repository.cancel(jobId, by) }
}
