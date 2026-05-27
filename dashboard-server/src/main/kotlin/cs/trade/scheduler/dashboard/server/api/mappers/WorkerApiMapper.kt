package cs.trade.scheduler.dashboard.server.api.mappers

import cs.trade.scheduler.shared.dto.WorkerDto
import cs.trade.scheduler.storage.postgres.domain.models.WorkerRow
import org.koin.core.annotation.Single
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes

@Single
public class WorkerApiMapper {

    public fun toDto(row: WorkerRow): WorkerDto {
        val now = Clock.System.now()
        val alive = (now - row.lastHeartbeat) < ALIVE_THRESHOLD
        return WorkerDto(
            nodeId = row.nodeId,
            host = row.host,
            tags = row.tags,
            startedAt = row.startedAt,
            lastHeartbeat = row.lastHeartbeat,
            inFlightCount = row.inFlightCount,
            inFlightByQueue = row.inFlightByQueue,
            alive = alive,
        )
    }

    public companion object {
        // DESIGN.md 13.4 sizing rule: heartbeatInterval (default 30s) <= lockDuration/3
        // (default 90s). After 1 minute without a heartbeat we treat the node as dead;
        // this matches "missed 2 heartbeats" for default config.
        public val ALIVE_THRESHOLD: kotlin.time.Duration = 1.minutes
    }
}
