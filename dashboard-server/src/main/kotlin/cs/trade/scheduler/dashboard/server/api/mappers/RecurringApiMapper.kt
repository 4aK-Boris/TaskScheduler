@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package cs.trade.scheduler.dashboard.server.api.mappers

import cs.trade.scheduler.shared.JobPriority
import cs.trade.scheduler.shared.dto.RecurringJobDto
import cs.trade.scheduler.shared.dto.RecurringRunDto
import cs.trade.scheduler.storage.postgres.domain.models.RecurringJobRow
import cs.trade.scheduler.storage.postgres.domain.models.RecurringRun
import org.koin.core.annotation.Single

@Single
public class RecurringApiMapper {
    /** [run] is the definition's live-or-latest execution, when it has one. */
    public fun toDto(row: RecurringJobRow, run: RecurringRun? = null): RecurringJobDto = RecurringJobDto(
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
        lastRun = run?.toDto(),
    )

    private fun RecurringRun.toDto(): RecurringRunDto = RecurringRunDto(
        jobId = jobId.toString(),
        state = state,
        progress = progress,
        progressSucceeded = progressSucceeded,
        progressFailed = progressFailed,
        progressTotal = progressTotal,
        startedAt = startedAt,
        durationMs = durationMs,
        updatedAt = updatedAt,
    )
}
