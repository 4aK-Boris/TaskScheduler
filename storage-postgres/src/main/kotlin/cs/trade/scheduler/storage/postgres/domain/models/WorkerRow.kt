package cs.trade.scheduler.storage.postgres.domain.models

import kotlin.time.Instant

// One row from the `worker` registry table. Updated by the WorkerRegistry loop in
// :engine-worker — every tick the worker upserts its own row with a fresh
// last_heartbeat. SafetyNetPoller / retention may delete dead rows later.
//
// `inFlightCount` (sum) stays as a separate column for cheap ORDER BY / filtering;
// `inFlightByQueue` carries the per-queue breakdown for the Workers dashboard so an
// operator can see "node X is full on heavy, idle on default".
public data class WorkerRow(
    val nodeId: String,
    val host: String,
    val tags: List<String>,
    val startedAt: Instant,
    val lastHeartbeat: Instant,
    val inFlightCount: Int,
    val inFlightByQueue: Map<String, Int> = emptyMap(),
)
