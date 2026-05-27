package cs.trade.scheduler.dashboard.web.domain.repositories

import cs.trade.scheduler.shared.dto.RecurringJobDto

public interface RecurringRepository {
    public suspend fun list(): List<RecurringJobDto>
    public suspend fun enable(id: String): Boolean
    public suspend fun disable(id: String): Boolean
}
