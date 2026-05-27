package cs.trade.scheduler.shared

import kotlinx.serialization.Serializable

/**
 * What to do with missed cron triggers when the scheduler was down.
 * See DESIGN.md section 8.5.
 */
@Serializable
public enum class MisfirePolicy {
    /** Do nothing, wait for the next scheduled run. */
    SKIP,

    /** Trigger exactly once to catch up (default). */
    CATCH_UP_ONE,

    /** Trigger every missed occurrence (use for snapshot/billing workloads). */
    CATCH_UP_ALL,
}
