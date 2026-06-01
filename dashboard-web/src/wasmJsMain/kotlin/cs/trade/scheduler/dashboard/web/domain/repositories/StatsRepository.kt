package cs.trade.scheduler.dashboard.web.domain.repositories

import cs.trade.scheduler.shared.dto.StatsOverviewResponse

public interface StatsRepository {
    /** [rangeHours] windows the terminal outcome counts (succeeded/failed/cancelled); live states ignore it. */
    public suspend fun overview(rangeHours: Int): StatsOverviewResponse
}
