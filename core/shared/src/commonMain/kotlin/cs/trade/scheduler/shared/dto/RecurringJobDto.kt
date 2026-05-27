package cs.trade.scheduler.shared.dto

import cs.trade.scheduler.shared.JobPriority
import cs.trade.scheduler.shared.MisfirePolicy
import kotlin.time.Instant
import kotlinx.serialization.Serializable

/** Recurring (cron) job definition. See DESIGN.md section 6 (recurring_job). */
@Serializable
public data class RecurringJobDto(
    val id: String,
    val cron: String,
    val timezone: String?,            // IANA TZ name, null = UTC
    val misfirePolicy: MisfirePolicy,
    val queue: String,
    val priority: JobPriority,
    val targetNode: String?,
    val targetTag: String?,
    val payloadType: String,
    val lastTriggeredAt: Instant?,
    val nextTriggerAt: Instant,
    val enabled: Boolean,
)
