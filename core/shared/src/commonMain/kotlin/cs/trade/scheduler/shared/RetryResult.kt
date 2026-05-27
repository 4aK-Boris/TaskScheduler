package cs.trade.scheduler.shared

import kotlinx.serialization.Serializable

/**
 * Outcome of [cs.trade.scheduler.core.backend.Scheduler.retry] — operator-initiated
 * MANUAL_RETRY action (DESIGN.md 18.6). Only FAILED rows are retryable. SUCCEEDED /
 * CANCELLED rows are deliberate end-states and shouldn't be revived.
 *
 *  - [RETRIED] — row was FAILED, flipped to ENQUEUED with `attempts = 0` and a fresh
 *    outbox row. Dashboard records a `MANUAL_RETRY` event with the actor.
 *  - [NOT_FAILED] — row exists but isn't in FAILED state (e.g. SUCCEEDED / CANCELLED /
 *    still in-flight). Caller should refresh and decide whether to act.
 *  - [NOT_FOUND] — no row with that id.
 *  - [CONFLICT] — version CAS lost repeatedly; the row is being concurrently mutated.
 *    Caller can retry the action after a short delay.
 *
 * **Not retried automatically:** DAG dependents that already cascaded to FAILED on
 * the original failure stay cascaded. Re-running the parent doesn't auto-revive them.
 * Operator must retry each branch they want re-run.
 */
@Serializable
public enum class RetryResult {
    RETRIED,
    NOT_FAILED,
    NOT_FOUND,
    CONFLICT,
}
