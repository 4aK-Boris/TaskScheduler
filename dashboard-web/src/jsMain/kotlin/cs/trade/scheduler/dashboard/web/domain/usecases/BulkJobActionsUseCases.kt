package cs.trade.scheduler.dashboard.web.domain.usecases

import cs.trade.scheduler.dashboard.web.domain.repositories.JobsRepository
import cs.trade.scheduler.shared.dto.BulkActionResponse

/** Wrap repo bulk methods so the component-layer Result<…> pattern stays uniform. */
public class BulkRetryJobsUseCase(private val repo: JobsRepository) {
    public suspend operator fun invoke(ids: List<String>, by: String? = null): Result<BulkActionResponse> =
        runCatching { repo.bulkRetry(ids, by) }
}

public class BulkCancelJobsUseCase(private val repo: JobsRepository) {
    public suspend operator fun invoke(ids: List<String>, by: String? = null): Result<BulkActionResponse> =
        runCatching { repo.bulkCancel(ids, by) }
}

public class BulkDeleteJobsUseCase(private val repo: JobsRepository) {
    public suspend operator fun invoke(ids: List<String>, by: String? = null): Result<BulkActionResponse> =
        runCatching { repo.bulkDelete(ids, by) }
}
