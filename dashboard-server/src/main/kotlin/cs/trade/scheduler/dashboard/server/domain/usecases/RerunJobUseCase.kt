@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package cs.trade.scheduler.dashboard.server.domain.usecases

import cs.trade.scheduler.core.backend.Scheduler
import cs.trade.scheduler.core.backend.usecases.BaseUseCase
import cs.trade.scheduler.core.backend.usecases.runCatchingWithLogging
import org.koin.core.annotation.Single
import kotlin.uuid.Uuid

/**
 * Bridges `POST /api/jobs/{id}/rerun` to [Scheduler.rerun]. Clones an existing job (same
 * payload/routing) into a brand-new ENQUEUED job. Returns the new job's id, or null when the
 * source job no longer exists (route → 404).
 */
@Single
public class RerunJobUseCase(
    private val scheduler: Scheduler,
) : BaseUseCase() {
    public suspend operator fun invoke(sourceJobId: Uuid): Result<Uuid?> =
        runCatchingWithLogging { scheduler.rerun(sourceJobId) }
}
