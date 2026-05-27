package cs.trade.scheduler.core.backend.handler.retry

import kotlin.time.Duration

/**
 * Strategy for computing backoff between retry attempts. See DESIGN.md section 16.
 * Skeleton — concrete [ExponentialBackoff] impl lives alongside as the engine matures.
 */
public interface RetryPolicy {
    public val maxAttempts: Int
    public fun nextBackoff(attempts: Int): Duration
}
