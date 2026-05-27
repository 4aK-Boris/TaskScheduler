package cs.trade.scheduler.storage.postgres.domain.repositories

import cs.trade.scheduler.storage.postgres.domain.models.WorkerRow
import kotlin.time.Instant

// Worker registry — every user-app node upserts its row on a heartbeat tick. The
// dashboard reads via findAll(); SafetyNetPoller / retention may delete dead rows.
public interface WorkerRepository {

    // INSERT ON CONFLICT(node_id) DO UPDATE. Bumps last_heartbeat + in_flight_count;
    // preserves started_at on conflict so the dashboard can show uptime.
    public suspend fun upsertHeartbeat(row: WorkerRow)

    public suspend fun findAll(limit: Int = 200): List<WorkerRow>

    public suspend fun findByNodeId(nodeId: String): WorkerRow?

    // Explicit removal on graceful WorkerPool.stop(). Returns true if a row existed.
    public suspend fun delete(nodeId: String): Boolean

    // For retention loop / SafetyNetPoller — remove workers whose last_heartbeat is
    // older than [olderThan]. Returns rows deleted.
    public suspend fun deleteDeadOlderThan(olderThan: Instant): Int
}
