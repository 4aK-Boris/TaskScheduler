package cs.trade.scheduler.dashboard.server.api.mappers

import cs.trade.scheduler.shared.dto.TypePauseDto
import cs.trade.scheduler.storage.postgres.domain.models.JobTypePauseRow
import org.koin.core.annotation.Single

@Single
public class TypePauseApiMapper {
    public fun toDto(row: JobTypePauseRow): TypePauseDto = TypePauseDto(
        payloadType = row.payloadType,
        pausedSince = row.pausedSince,
        pausedBy = row.pausedBy,
        reason = row.reason,
    )
}
