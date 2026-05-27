package cs.trade.scheduler.core.backend.handler.retry

import kotlin.math.pow
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * No retries — first failure is terminal. Useful for idempotency-sensitive jobs that
 * should never be re-tried automatically (e.g. external API calls without dedup).
 *
 * `maxAttempts = 1` mirrors the "one shot" intent — the job runs once.
 */
public object NoRetry : RetryPolicy {
    override val maxAttempts: Int = 1
    override fun nextBackoff(attempts: Int): Duration = Duration.ZERO
}

/**
 * Fixed wait between every retry. Good default for "the downstream is flaky but recovers
 * in seconds" — predictable, easy to reason about.
 */
public data class FixedDelay(
    val delay: Duration,
    override val maxAttempts: Int = 3,
) : RetryPolicy {
    init { require(maxAttempts >= 1) { "maxAttempts must be ≥ 1, got $maxAttempts" } }
    override fun nextBackoff(attempts: Int): Duration = delay
}

/**
 * Standard exponential backoff: `initialDelay * multiplier^(attempts-1)`, capped at
 * [maxDelay]. The default `multiplier = 2.0, initial = 1s, max = 5m, maxAttempts = 5`
 * gives a sequence 1s → 2s → 4s → 8s → 16s for a job that fails through every attempt.
 *
 * `attempts` is the 1-based count of how many times the job has already been picked up —
 * the first failure passes `attempts = 1` and gets back [initialDelay].
 *
 * No jitter in this skeleton — uniform retries from many failing workers can thunder back
 * onto the downstream. Add a jitter knob if/when that becomes a problem (DESIGN.md 16.5).
 */
public data class ExponentialBackoff(
    val initialDelay: Duration = 1.seconds,
    val maxDelay: Duration = 5.minutes,
    val multiplier: Double = 2.0,
    override val maxAttempts: Int = 5,
) : RetryPolicy {

    init {
        require(initialDelay.isPositive()) { "initialDelay must be positive, got $initialDelay" }
        require(maxDelay >= initialDelay) { "maxDelay ($maxDelay) must be ≥ initialDelay ($initialDelay)" }
        require(multiplier > 1.0) { "multiplier must be > 1.0, got $multiplier" }
        require(maxAttempts >= 1) { "maxAttempts must be ≥ 1, got $maxAttempts" }
    }

    override fun nextBackoff(attempts: Int): Duration {
        val exponent = (attempts - 1).coerceAtLeast(0)
        val factor = multiplier.pow(exponent)
        val raw = (initialDelay.inWholeMilliseconds.toDouble() * factor)
            .coerceAtMost(maxDelay.inWholeMilliseconds.toDouble())
            .toLong()
        return raw.milliseconds
    }
}
