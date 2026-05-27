package cs.trade.scheduler.dashboard.server.domain.usecases

import cs.trade.scheduler.core.backend.usecases.BaseUseCase
import cs.trade.scheduler.core.backend.usecases.runCatchingWithLogging
import cs.trade.scheduler.storage.postgres.domain.models.Job
import cs.trade.scheduler.storage.postgres.domain.models.JobListFilter
import cs.trade.scheduler.storage.postgres.domain.models.PagedResult
import cs.trade.scheduler.storage.postgres.domain.repositories.JobRepository
import org.koin.core.annotation.Single

/**
 * Per the "1 function repo ↔ 1 UseCase" rule (DESIGN.md 3.3), this is the only caller of
 * [JobRepository.findAll] from `:dashboard-server`. Routes go through this UseCase, not
 * the repository directly.
 */
@Single
public class GetJobsListUseCase(
    private val jobs: JobRepository,
) : BaseUseCase() {

    public suspend operator fun invoke(
        filter: JobListFilter,
        page: Int,
        size: Int,
    ): Result<PagedResult<Job>> = runCatchingWithLogging {
        jobs.findAll(filter, page, size)
    }
}
