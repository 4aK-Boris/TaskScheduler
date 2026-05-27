package cs.trade.scheduler.dashboard.server.domain.usecases

import cs.trade.scheduler.core.backend.usecases.BaseUseCase
import cs.trade.scheduler.core.backend.usecases.runCatchingWithLogging
import cs.trade.scheduler.storage.postgres.domain.models.JobTypePauseRow
import cs.trade.scheduler.storage.postgres.domain.repositories.JobTypePauseRepository
import org.koin.core.annotation.Single

@Single
public class ListJobTypePausesUseCase(
    private val pauses: JobTypePauseRepository,
) : BaseUseCase() {
    public suspend operator fun invoke(): Result<List<JobTypePauseRow>> =
        runCatchingWithLogging { pauses.findAll() }
}
