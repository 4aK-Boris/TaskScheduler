package cs.trade.scheduler.dashboard.server.domain.usecases

import cs.trade.scheduler.core.backend.events.EventBus
import cs.trade.scheduler.core.backend.usecases.BaseUseCase
import cs.trade.scheduler.core.backend.usecases.runCatchingWithLogging
import cs.trade.scheduler.shared.events.WebSocketEvent
import cs.trade.scheduler.storage.postgres.domain.repositories.JobTypePauseRepository
import org.koin.core.annotation.Single
import kotlin.time.Clock

@Single
public class UnpauseJobTypeUseCase(
    private val pauses: JobTypePauseRepository,
    private val eventBus: EventBus,
) : BaseUseCase() {
    public suspend operator fun invoke(payloadType: String): Result<Boolean> =
        runCatchingWithLogging {
            val removed = pauses.unpause(payloadType)
            if (removed) {
                // Publish only when a row actually existed — a 404 (no-op) shouldn't
                // make dashboard clients churn through a phantom unpause refresh.
                eventBus.publish(
                    WebSocketEvent.JobTypeUnpaused(
                        payloadType = payloadType,
                        at = Clock.System.now(),
                    ),
                )
            }
            removed
        }
}
