package cs.trade.scheduler.dashboard.web.domain.usecases

import cs.trade.scheduler.dashboard.web.domain.repositories.UpcomingRepository
import cs.trade.scheduler.shared.dto.UpcomingResponse

/** Wraps [UpcomingRepository.upcoming] — the forward agenda for the Upcoming screen. */
public class GetUpcomingUseCase(
    private val repository: UpcomingRepository,
) {
    public suspend operator fun invoke(withinMinutes: Int): Result<UpcomingResponse> =
        runCatching { repository.upcoming(withinMinutes) }
}
