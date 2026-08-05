package cs.trade.scheduler.dashboard.web.domain.usecases

import cs.trade.scheduler.dashboard.web.domain.repositories.WorkersRepository
import cs.trade.scheduler.shared.dto.WorkerDto

public class ListWorkersUseCase(private val repo: WorkersRepository) {
    public suspend operator fun invoke(): Result<List<WorkerDto>> = runCatching { repo.list() }
}
