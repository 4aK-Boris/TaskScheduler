package cs.trade.scheduler.shared

import kotlinx.serialization.Serializable

/**
 * How an operator-initiated retry (DESIGN.md 9.5) should treat the job's attempt budget.
 * Surfaced as the two dashboard buttons on a FAILED job:
 *
 *  - [FRESH_BUDGET] — the "Retry" button. Resets `attempts` to 0 so the job runs again with
 *    its full retry budget (up to `max_attempts` auto-retries). Semantics: "I fixed the root
 *    cause, start over." An exhausted job (`attempts == max_attempts`) couldn't otherwise be
 *    rerun at all.
 *  - [ONCE] — the "Retry +1" button. Parks `attempts` one below `max_attempts`, so the job
 *    runs exactly once more and — if it fails again — drops straight back to FAILED with no
 *    auto-retry churn. Semantics: "probably a transient blip, give it one more shot without
 *    granting a whole fresh budget." Deterministic regardless of how the row reached FAILED
 *    (exhausted retries OR a non-retriable / schema error), since the worker's
 *    `attempts < max_attempts` gate fails the instant this run does.
 */
@Serializable
public enum class RetryMode {
    FRESH_BUDGET,
    ONCE,
}
