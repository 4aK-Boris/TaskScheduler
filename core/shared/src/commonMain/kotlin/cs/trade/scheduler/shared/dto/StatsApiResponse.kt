package cs.trade.scheduler.shared.dto

import kotlinx.serialization.Serializable

// Wire DTO for GET /api/stats/overview. MVP scope: per-state counters from the job
// table. Queue snapshots and worker summary land alongside WorkerRepository impl.
@Serializable
public data class StatsOverviewResponse(
    val enqueued: Long,
    val processing: Long,
    val awaitingRetry: Long,
    val awaitingDeps: Long,
    val scheduled: Long,
    val succeeded: Long,
    val failed: Long,
    val cancelled: Long,
)
