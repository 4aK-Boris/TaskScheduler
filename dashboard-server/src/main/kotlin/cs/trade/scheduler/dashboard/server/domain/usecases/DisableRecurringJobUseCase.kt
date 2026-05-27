package cs.trade.scheduler.dashboard.server.domain.usecases

import cs.trade.scheduler.core.backend.usecases.BaseUseCase
import cs.trade.scheduler.core.backend.usecases.runCatchingWithLogging
import cs.trade.scheduler.storage.postgres.domain.repositories.RecurringJobRepository
import org.koin.core.annotation.Single

@Single
public class DisableRecurringJobUseCase(
    private val recurring: RecurringJobRepository,
) : BaseUseCase() {
    public suspend operator fun invoke(id: String): Result<Boolean> =
        runCatchingWithLogging { recurring.disable(id) }
}
