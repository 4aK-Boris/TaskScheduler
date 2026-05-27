package cs.trade.scheduler.shared.dto

import kotlinx.serialization.Serializable

/**
 * Backpressure indicator surface for the dashboard (DESIGN.md 20.10). One entry per
 * queue with at least one non-terminal job; queues without active jobs are absent.
 *
 * Status thresholds (configurable on the server side):
 *  - **NORMAL**: depth below `elevatedThreshold` — green badge, business as usual.
 *  - **ELEVATED**: depth in `[elevatedThreshold, overloadedThreshold)` — yellow badge,
 *    flag operators that the queue is filling faster than it's draining.
 *  - **OVERLOADED**: depth at or above `overloadedThreshold` — red badge, the queue is
 *    backing up and the user should consider scaling workers or pausing producers.
 *
 * Status is server-side computed (one source of truth) so client-side rendering doesn't
 * need to know the thresholds.
 */
@Serializable
public data class QueueHealthDto(
    /** Queue name as it appears in `job.queue` and the worker pool's `queue("…")` DSL. */
    val queue: String,
    /** Non-terminal job count (ENQUEUED + PROCESSING + AWAITING_RETRY + AWAITING_DEPS + SCHEDULED). */
    val depth: Long,
    val status: QueueHealthStatus,
)

@Serializable
public enum class QueueHealthStatus { NORMAL, ELEVATED, OVERLOADED }

@Serializable
public data class QueueHealthResponse(
    val items: List<QueueHealthDto>,
)
