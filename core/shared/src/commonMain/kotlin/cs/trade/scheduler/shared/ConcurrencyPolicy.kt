package cs.trade.scheduler.shared

import kotlinx.serialization.Serializable

/**
 * What [cs.trade.scheduler.core.backend.Scheduler.enqueueOnce] does when its `key` already has
 * an active (non-terminal) job — the "collision" case. See DESIGN.md section 17.4.
 *
 * Cancellation in this system is cooperative (a PROCESSING handler must observe the request),
 * so [REPLACE] cannot kill a running job instantly — the semantics below spell out what happens.
 */
@Serializable
public enum class ConcurrencyPolicy {
    /**
     * Coalesce: return the existing active job's id and DO NOT enqueue a duplicate.
     * Default — preserves the original `enqueueOnce` behaviour.
     */
    SKIP,

    /**
     * Cancel the active job and run the new one. A not-yet-running incumbent (ENQUEUED /
     * SCHEDULED / AWAITING_RETRY) is cancelled immediately and the new job runs right away.
     * A still-RUNNING (PROCESSING) incumbent is cancel-requested cooperatively and the new job
     * runs only AFTER it actually stops — never two concurrent executions for the same key.
     */
    REPLACE,

    /**
     * Leave the active job alone; the new one waits for it to reach any terminal state
     * (succeeded / failed / cancelled), then runs. A second `ENQUEUE_AFTER` for a key that
     * already has a waiter returns that waiter's id (at most one queued successor per key).
     */
    ENQUEUE_AFTER,
}
