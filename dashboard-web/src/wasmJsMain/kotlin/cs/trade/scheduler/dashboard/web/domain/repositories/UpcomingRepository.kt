package cs.trade.scheduler.dashboard.web.domain.repositories

import cs.trade.scheduler.shared.dto.UpcomingResponse

public interface UpcomingRepository {
    /** Forward agenda of predicted runs within the next [withinMinutes], soonest-first. */
    public suspend fun upcoming(withinMinutes: Int): UpcomingResponse
}
