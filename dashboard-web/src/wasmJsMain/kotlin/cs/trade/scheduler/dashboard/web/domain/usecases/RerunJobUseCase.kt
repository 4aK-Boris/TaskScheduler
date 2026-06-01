package cs.trade.scheduler.dashboard.web.domain.usecases

import cs.trade.scheduler.dashboard.web.domain.repositories.JobsRepository

/** Wraps [JobsRepository.rerun]. Result holds the new job's id, or null if the source job is gone. */
public class RerunJobUseCase(
    private val repository: JobsRepository,
) {
    public suspend operator fun invoke(jobId: String): Result<String?> =
        runCatching { repository.rerun(jobId) }
}
