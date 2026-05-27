package cs.trade.scheduler.dashboard.web.domain.usecases

import cs.trade.scheduler.dashboard.web.domain.repositories.RecurringRepository
import cs.trade.scheduler.shared.dto.RecurringJobDto

public class ListRecurringJobsUseCase(private val repo: RecurringRepository) {
    public suspend operator fun invoke(): Result<List<RecurringJobDto>> = runCatching { repo.list() }
}

public class EnableRecurringJobUseCase(private val repo: RecurringRepository) {
    public suspend operator fun invoke(id: String): Result<Boolean> = runCatching { repo.enable(id) }
}

public class DisableRecurringJobUseCase(private val repo: RecurringRepository) {
    public suspend operator fun invoke(id: String): Result<Boolean> = runCatching { repo.disable(id) }
}
