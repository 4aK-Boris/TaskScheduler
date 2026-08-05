package cs.trade.scheduler.dashboard.web.domain.repositories

import cs.trade.scheduler.shared.dto.TypeStatsResponse

public interface TypeStatsRepository {
    /** GET /api/stats/types?range={rangeHours}h. */
    public suspend fun list(rangeHours: Int): TypeStatsResponse
}
