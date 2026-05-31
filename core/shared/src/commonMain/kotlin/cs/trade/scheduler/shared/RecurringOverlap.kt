package cs.trade.scheduler.shared

import kotlinx.serialization.Serializable

/**
 * What a recurring (cron) definition does when, at the next trigger, its previously fired
 * instance is still active (non-terminal). Distinct from [MisfirePolicy], which governs cron
 * slots missed during downtime — this governs a previous run that is simply still in flight.
 * See DESIGN.md section 8.5.
 */
@Serializable
public enum class RecurringOverlap {
    /** Fire on every trigger regardless — instances may run concurrently. Default (current behaviour). */
    ALLOW,

    /** Skip the new fire while the previous instance is still active. */
    SKIP,

    /**
     * Cancel the previous instance and fire a new one. A still-RUNNING previous instance is
     * cancel-requested cooperatively and the new instance runs only after it stops.
     */
    REPLACE,
}
