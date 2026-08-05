package cs.trade.scheduler.dashboard.web.domain.usecases

import cs.trade.scheduler.dashboard.web.domain.repositories.RecurringRepository

/** Wraps [RecurringRepository.trigger]. Result holds the new job's id, or null if the id is unknown. */
public class TriggerRecurringJobUseCase(
    private val repository: RecurringRepository,
) {
    public suspend operator fun invoke(id: String): Result<String?> =
        runCatching { repository.trigger(id) }
}
