package cs.trade.scheduler.shared.dto

import kotlinx.serialization.Serializable

/** `GET /api/stats/overview` payload. See DESIGN.md section 9.1. */
@Serializable
public data class StatsOverview(
    val jobs: JobsCounters,
    val queues: List<QueueSnapshot>,
    val workers: WorkersSummary,
)

@Serializable
public data class JobsCounters(
    val enqueued: Long,
    val processing: Long,
    val awaitingRetry: Long,
    val succeeded24h: Long,
    val failed24h: Long,
)

@Serializable
public data class QueueSnapshot(
    val name: String,
    val depth: Long,
    val inFlight: Long,
    val throughputPerMin: Double,
)

@Serializable
public data class WorkersSummary(
    val alive: Int,
    val total: Int,
)

/** `GET /api/stats/types` row. See DESIGN.md section 22.4. */
@Serializable
public data class JobTypeStats(
    val type: String,
    val queue: String,
    val successCount: Long,
    val failedCount: Long,
    val retryCount: Long,
    val avgDurationMs: Long,
    val minDurationMs: Long,
    val maxDurationMs: Long,
    val p95DurationMs: Long,
    val paused: Boolean,
)
