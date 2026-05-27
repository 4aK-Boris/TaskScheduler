package cs.trade.scheduler.shared.dto

import kotlin.time.Instant
import kotlinx.serialization.Serializable

/** Worker (user-app node) snapshot. See DESIGN.md section 6 (worker). */
@Serializable
public data class WorkerDto(
    val nodeId: String,
    val host: String,
    val tags: List<String>,
    val startedAt: Instant,
    val lastHeartbeat: Instant,
    val inFlightCount: Int,
    // Per-queue breakdown. Empty for legacy worker rows that pre-date V2; the UI falls
    // back to just showing inFlightCount in that case.
    val inFlightByQueue: Map<String, Int> = emptyMap(),
    val alive: Boolean,                // last_heartbeat < 1 min ago
)
