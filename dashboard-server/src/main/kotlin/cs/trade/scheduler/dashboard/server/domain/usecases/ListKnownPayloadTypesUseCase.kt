package cs.trade.scheduler.dashboard.server.domain.usecases

import cs.trade.scheduler.core.backend.usecases.BaseUseCase
import cs.trade.scheduler.core.backend.usecases.runCatchingWithLogging
import cs.trade.scheduler.storage.postgres.domain.repositories.JobRepository
import cs.trade.scheduler.storage.postgres.domain.repositories.JobTypePauseRepository
import org.koin.core.annotation.Single

/**
 * Returns the union of "ever-enqueued" payload types (from the `job` table) and
 * currently-paused types (from `job_type_pause`). Paused types might no longer have
 * any job rows around if retention nuked them — including them ensures the dashboard
 * dropdown still surfaces them so operators can unpause without retyping.
 *
 * Result is a sorted, deduplicated list. See DESIGN.md 22.1.
 */
@Single
public class ListKnownPayloadTypesUseCase(
    private val jobs: JobRepository,
    private val pauses: JobTypePauseRepository,
) : BaseUseCase() {

    public suspend operator fun invoke(): Result<List<String>> = runCatchingWithLogging {
        val fromJobs = jobs.findDistinctPayloadTypes(limit = MAX_TYPES)
        val paused = pauses.findPausedTypes()
        (fromJobs.toSet() + paused).sorted()
    }

    private companion object {
        // Mirrors JobRepository.findDistinctPayloadTypes default. > 1000 distinct types
        // is pathological — apps that hit this should curate their handler set.
        const val MAX_TYPES = 1000
    }
}
