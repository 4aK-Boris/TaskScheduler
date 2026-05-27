package cs.trade.scheduler.dashboard.server.api.mappers

import cs.trade.scheduler.shared.JobPriority
import cs.trade.scheduler.shared.dto.RecurringJobDto
import cs.trade.scheduler.storage.postgres.domain.models.RecurringJobRow
import org.koin.core.annotation.Single

@Single
public class RecurringApiMapper {
    public fun toDto(row: RecurringJobRow): RecurringJobDto = RecurringJobDto(
        id = row.id,
        cron = row.cron,
        timezone = row.timezone,
        misfirePolicy = row.misfirePolicy,
        queue = row.queue,
        priority = JobPriority(row.priority),
        targetNode = row.targetNode,
        targetTag = row.targetTag,
        payloadType = row.payloadType,
        lastTriggeredAt = row.lastTriggeredAt,
        nextTriggerAt = row.nextTriggerAt,
        enabled = row.enabled,
    )
}
