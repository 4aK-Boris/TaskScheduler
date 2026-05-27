package cs.trade.scheduler.dashboard.web.domain.repositories

import cs.trade.scheduler.shared.dto.StatsOverviewResponse

public interface StatsRepository {
    public suspend fun overview(): StatsOverviewResponse
}
