package cs.trade.scheduler.dashboard.server.domain.usecases

import cs.trade.scheduler.core.backend.usecases.BaseUseCase
import cs.trade.scheduler.core.backend.usecases.runCatchingWithLogging
import cs.trade.scheduler.storage.postgres.domain.models.RecurringRun
import cs.trade.scheduler.storage.postgres.domain.repositories.JobRepository
import org.koin.core.annotation.Single

/**
 * The live-or-latest run of each given recurring definition, keyed by definition id.
 *
 * Wraps the repository's batch lookup so the Recurring listing stays two queries — the definitions
 * and their runs — instead of one per row.
 */
@Single
public class ListRecurringRunsUseCase(
    private val jobs: JobRepository,
) : BaseUseCase() {

    public suspend operator fun invoke(recurringIds: Collection<String>): Result<Map<String, RecurringRun>> =
        runCatchingWithLogging { jobs.findLatestRunsByRecurringIds(recurringIds) }
}
