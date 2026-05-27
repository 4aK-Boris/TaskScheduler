package cs.trade.scheduler.dashboard.server.domain.usecases

import cs.trade.scheduler.core.backend.usecases.BaseUseCase
import cs.trade.scheduler.core.backend.usecases.runCatchingWithLogging
import cs.trade.scheduler.storage.postgres.domain.models.WorkerRow
import cs.trade.scheduler.storage.postgres.domain.repositories.WorkerRepository
import org.koin.core.annotation.Single

@Single
public class ListWorkersUseCase(
    private val workers: WorkerRepository,
) : BaseUseCase() {
    public suspend operator fun invoke(): Result<List<WorkerRow>> =
        runCatchingWithLogging { workers.findAll() }
}
