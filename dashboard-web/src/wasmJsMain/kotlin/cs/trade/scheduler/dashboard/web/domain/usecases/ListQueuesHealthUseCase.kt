package cs.trade.scheduler.dashboard.web.domain.usecases

import cs.trade.scheduler.dashboard.web.domain.repositories.QueueHealthRepository
import cs.trade.scheduler.shared.dto.QueueHealthDto

public class ListQueuesHealthUseCase(private val repo: QueueHealthRepository) {
    public suspend operator fun invoke(): Result<List<QueueHealthDto>> = runCatching { repo.list() }
}
