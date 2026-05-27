package cs.trade.scheduler.dashboard.web.domain.repositories

import cs.trade.scheduler.shared.dto.WorkerDto

public interface WorkersRepository {
    public suspend fun list(): List<WorkerDto>
}
