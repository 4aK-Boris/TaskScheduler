package cs.trade.scheduler.shared

import kotlinx.serialization.Serializable

/**
 * Lifecycle state of a job. See DESIGN.md section 5 (state machine).
 *
 * Terminal states: [SUCCEEDED], [FAILED], [CANCELLED].
 */
@Serializable
public enum class JobState {
    AWAITING_DEPS,
    SCHEDULED,
    ENQUEUED,
    PROCESSING,
    AWAITING_RETRY,
    SUCCEEDED,
    FAILED,
    CANCELLED;

    public val isTerminal: Boolean
        get() = this == SUCCEEDED || this == FAILED || this == CANCELLED
}
