package cs.trade.scheduler.dashboard.web.domain.repositories

import cs.trade.scheduler.shared.dto.QueueHealthDto

public interface QueueHealthRepository {
    /** Snapshot of per-queue backpressure (DESIGN.md 20.10). Only queues with active jobs. */
    public suspend fun list(): List<QueueHealthDto>
}
