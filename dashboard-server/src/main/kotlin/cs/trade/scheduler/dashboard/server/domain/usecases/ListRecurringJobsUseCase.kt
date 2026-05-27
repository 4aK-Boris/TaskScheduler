package cs.trade.scheduler.dashboard.server.domain.usecases

import cs.trade.scheduler.core.backend.usecases.BaseUseCase
import cs.trade.scheduler.core.backend.usecases.runCatchingWithLogging
import cs.trade.scheduler.storage.postgres.domain.models.RecurringJobRow
import cs.trade.scheduler.storage.postgres.domain.repositories.RecurringJobRepository
import org.koin.core.annotation.Single

// "1 function repo <-> 1 UseCase" — wraps the single findAll call.
@Single
public class ListRecurringJobsUseCase(
    private val recurring: RecurringJobRepository,
) : BaseUseCase() {

    public suspend operator fun invoke(limit: Int = DEFAULT_LIMIT): Result<List<RecurringJobRow>> =
        runCatchingWithLogging { recurring.findAll(limit) }

    public companion object {
        public const val DEFAULT_LIMIT: Int = 500
    }
}
