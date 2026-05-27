package cs.trade.scheduler.shared

import kotlinx.serialization.Serializable

/**
 * Outcome of [cs.trade.scheduler.core.backend.Scheduler.cancel]. See DESIGN.md 22.7.
 *
 *  - [CANCELLED] — row was non-terminal and not in flight; we flipped it to CANCELLED
 *    in one UPDATE. Any pending outbox row is harmless (pickup will see CANCELLED and skip).
 *  - [CANCEL_REQUESTED] — row was PROCESSING. We stamped `cancel_requested_at`; the handler
 *    must poll [cs.trade.scheduler.core.backend.handler.JobContext.isCancellationRequested]
 *    and throw [cs.trade.scheduler.core.backend.handler.JobCancellationException]
 *    (or just return) to opt into cooperative cancel.
 *  - [ALREADY_TERMINAL] — row was already SUCCEEDED / FAILED / CANCELLED.
 *  - [NOT_FOUND] — no row with that id.
 */
@Serializable
public enum class CancelResult {
    CANCELLED,
    CANCEL_REQUESTED,
    ALREADY_TERMINAL,
    NOT_FOUND,
}
