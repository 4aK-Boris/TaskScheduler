package cs.trade.scheduler.dashboard.web.domain.usecases

import cs.trade.scheduler.dashboard.web.domain.repositories.JobsRepository
import cs.trade.scheduler.shared.RetryMode
import cs.trade.scheduler.shared.RetryResult

/** Wraps [JobsRepository.retry]. `by` flows from the auth principal (when wired). */
public class RetryJobUseCase(
    private val repository: JobsRepository,
) {
    public suspend operator fun invoke(
        jobId: String,
        by: String? = null,
        mode: RetryMode = RetryMode.FRESH_BUDGET,
    ): Result<RetryResult> =
        runCatching { repository.retry(jobId, by, mode) }
}
