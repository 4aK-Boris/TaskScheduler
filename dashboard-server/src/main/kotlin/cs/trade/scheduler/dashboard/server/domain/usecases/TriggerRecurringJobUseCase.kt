@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package cs.trade.scheduler.dashboard.server.domain.usecases

import cs.trade.scheduler.core.backend.Scheduler
import cs.trade.scheduler.core.backend.usecases.BaseUseCase
import cs.trade.scheduler.core.backend.usecases.runCatchingWithLogging
import org.koin.core.annotation.Single
import kotlin.uuid.Uuid

/**
 * Bridges `POST /api/recurring/{id}/trigger` to [Scheduler.triggerRecurringNow]. Fires the
 * definition once off-schedule, reusing its stored payload. Returns the new job's id, or null
 * when no definition with that id exists (route → 404).
 */
@Single
public class TriggerRecurringJobUseCase(
    private val scheduler: Scheduler,
) : BaseUseCase() {
    public suspend operator fun invoke(id: String): Result<Uuid?> =
        runCatchingWithLogging { scheduler.triggerRecurringNow(id) }
}
