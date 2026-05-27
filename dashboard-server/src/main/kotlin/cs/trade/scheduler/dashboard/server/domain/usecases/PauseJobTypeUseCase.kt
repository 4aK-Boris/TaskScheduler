package cs.trade.scheduler.dashboard.server.domain.usecases

import cs.trade.scheduler.core.backend.events.EventBus
import cs.trade.scheduler.core.backend.usecases.BaseUseCase
import cs.trade.scheduler.core.backend.usecases.runCatchingWithLogging
import cs.trade.scheduler.shared.events.WebSocketEvent
import cs.trade.scheduler.storage.postgres.domain.repositories.JobTypePauseRepository
import org.koin.core.annotation.Single
import kotlin.time.Clock

@Single
public class PauseJobTypeUseCase(
    private val pauses: JobTypePauseRepository,
    private val eventBus: EventBus,
) : BaseUseCase() {
    public suspend operator fun invoke(
        payloadType: String,
        actor: String,
        reason: String?,
    ): Result<Unit> = runCatchingWithLogging {
        val now = Clock.System.now()
        pauses.pause(
            payloadType = payloadType,
            pausedBy = actor,
            reason = reason,
            pausedSince = now,
        )
        eventBus.publish(
            WebSocketEvent.JobTypePaused(
                payloadType = payloadType,
                by = actor,
                reason = reason,
                at = now,
            ),
        )
    }
}
