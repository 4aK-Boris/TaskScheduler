package cs.trade.scheduler.shared.dto

import cs.trade.scheduler.shared.JobPriority
import cs.trade.scheduler.shared.JobState
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

    /**
     * The run worth showing for this definition: the one in flight if it is running, otherwise the
     * most recent finished one. `null` when the definition has never fired — or last fired before
     * the V9 migration, which is what introduced the job → definition link.
     *
     * Drives the Recurring screen's live indicator and its row click-through.
     */
    val lastRun: RecurringRunDto? = null,
)

/** A single execution of a [RecurringJobDto], trimmed to what the Recurring screen renders. */
@Serializable
public data class RecurringRunDto(
    val jobId: String,
    val state: JobState,

    /** Fraction 0..1 as last reported by the handler, or `null` if it never reported. */
    val progress: Float? = null,

    /** Counting-progress figures; `null` unless the handler used the counting API. */
    val progressSucceeded: Long? = null,
    val progressFailed: Long? = null,
    val progressTotal: Long? = null,

    val startedAt: Instant? = null,
    val durationMs: Long? = null,

    /** Last state change — for a finished run, when it finished. */
    val updatedAt: Instant,
) {
    /** True while this run still occupies the definition (queued, running, or awaiting retry). */
    val isLive: Boolean get() = !state.isTerminal
}
