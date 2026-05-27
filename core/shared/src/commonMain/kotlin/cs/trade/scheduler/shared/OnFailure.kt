package cs.trade.scheduler.shared

import kotlinx.serialization.Serializable

/**
 * Behavior of a dependent (child) job when one of its parents transitions to FAILED.
 * See DESIGN.md section 7.4.
 */
@Serializable
public enum class OnFailure {
    /** Child becomes FAILED too. Default. */
    PROPAGATE_FAILURE,

    /** Child becomes CANCELLED (no failure propagation, just stops the branch). */
    CANCEL_CHILD,

    /** Child proceeds as if parent succeeded (pending_deps decrement). */
    IGNORE,
}
