package cs.trade.scheduler.engine.worker.infrastructure

import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * Per-queue circuit breaker (DESIGN.md 20.7). Three-state machine:
 *
 *  - **CLOSED** — normal. Every outcome is recorded. When the rolling window's failure
 *    rate exceeds `errorRateThreshold` AND has at least `minSamples` data points, the
 *    breaker trips to OPEN.
 *  - **OPEN** — pickups are refused (caller releases the message back to Rabbit). After
 *    `openDuration` since trip-time, transitions to HALF_OPEN.
 *  - **HALF_OPEN** — allows exactly ONE concurrent pickup as a probe. Subsequent calls
 *    to [tryAcquire] return false until the probe reports back. A successful probe
 *    closes the breaker (and clears the window); a failing probe re-opens for another
 *    cycle.
 *
 * Concurrency model: all state is per-queue and lives in a `BreakerState` guarded by
 * its own intrinsic monitor lock. Contention is bounded by per-queue throughput; the
 * registry is `ConcurrentHashMap` so adding queues doesn't synchronise existing ones.
 *
 * Samples are kept in a list of `(timestamp, success)` pairs. We don't cap size by N —
 * we cap by [CircuitBreakerConfig.sampleWindow]. Pruning happens on each [record] call
 * (lazy eviction at the cost of growing the list during quiet periods; bounded in
 * practice by queue throughput * sampleWindow).
 */
public class CircuitBreakerRegistry(
    private val clock: () -> Instant = { Clock.System.now() },
) {

    private val log = LoggerFactory.getLogger(javaClass)

    public enum class State { CLOSED, OPEN, HALF_OPEN }

    private data class BreakerState(
        val config: CircuitBreakerConfig,
        // Rolling window of (timestamp, success) pairs.
        val samples: ArrayDeque<Sample> = ArrayDeque(),
        @Volatile var state: State = State.CLOSED,
        // When the breaker tripped to OPEN. Drives the HALF_OPEN transition.
        @Volatile var openedAt: Instant? = null,
        // True while a HALF_OPEN probe is in-flight. Reset on the probe's outcome.
        @Volatile var probeInFlight: Boolean = false,
    )

    private data class Sample(val at: Instant, val success: Boolean)

    private val breakers = ConcurrentHashMap<String, BreakerState>()

    /**
     * Register a queue for circuit-breaker tracking. Idempotent — re-registering with a
     * fresh config replaces state (mirrors PrefetchTuner's register semantics).
     */
    public fun register(queue: String, config: CircuitBreakerConfig) {
        breakers[queue] = BreakerState(config = config)
    }

    /**
     * Should the caller dispatch a freshly-picked job? Side-effect: a HALF_OPEN return
     * value of `true` flips `probeInFlight` so subsequent calls return `false` until
     * the probe reports its outcome via [record].
     *
     * Queues that aren't registered always return `true` — function-ref like the prefetch
     * tuner.
     */
    public fun tryAcquire(queue: String): Boolean {
        val state = breakers[queue] ?: return true
        synchronized(state) {
            maybeTransitionToHalfOpen(state)
            return when (state.state) {
                State.CLOSED -> true
                State.OPEN -> false
                State.HALF_OPEN -> {
                    if (state.probeInFlight) {
                        false
                    } else {
                        state.probeInFlight = true
                        true
                    }
                }
            }
        }
    }

    /**
     * Record an outcome. Updates the rolling window and applies state transitions.
     * Tolerates queues that aren't registered (no-op).
     */
    public fun record(queue: String, success: Boolean) {
        val state = breakers[queue] ?: return
        synchronized(state) {
            val now = clock()
            state.samples.addLast(Sample(at = now, success = success))
            evictOld(state, now)
            when (state.state) {
                State.CLOSED -> maybeTripToOpen(queue, state, now)
                State.HALF_OPEN -> {
                    // Probe outcome: success → CLOSED (and clear window so we don't
                    // re-trip on old failures), failure → OPEN for another openDuration.
                    state.probeInFlight = false
                    if (success) {
                        log.info("CircuitBreaker[{}] HALF_OPEN → CLOSED (probe succeeded)", queue)
                        state.state = State.CLOSED
                        state.openedAt = null
                        state.samples.clear()
                    } else {
                        log.warn("CircuitBreaker[{}] HALF_OPEN → OPEN (probe failed)", queue)
                        state.state = State.OPEN
                        state.openedAt = now
                    }
                }
                State.OPEN -> {
                    // Shouldn't see samples while OPEN (we returned false from
                    // tryAcquire), but if one slips through a tight race it's harmless
                    // to just bank the outcome. No state change.
                }
            }
        }
    }

    /** Current state — exposed for tests and metrics. Reads are non-blocking (volatile). */
    public fun stateOf(queue: String): State? = breakers[queue]?.state

    /** True iff a queue's breaker has been registered. Helps callers skip the tryAcquire fast-path. */
    public fun isRegistered(queue: String): Boolean = breakers.containsKey(queue)

    private fun maybeTransitionToHalfOpen(state: BreakerState) {
        if (state.state != State.OPEN) return
        val openedAt = state.openedAt ?: return
        val now = clock()
        if (now - openedAt >= state.config.openDuration) {
            state.state = State.HALF_OPEN
            state.probeInFlight = false
            // Don't clear samples — if the probe fails we'd want the SAME pre-trip
            // context. Samples will age out naturally if HALF_OPEN drags on.
        }
    }

    private fun maybeTripToOpen(queue: String, state: BreakerState, now: Instant) {
        val total = state.samples.size
        if (total < state.config.minSamples) return
        val failures = state.samples.count { !it.success }
        val rate = failures.toDouble() / total
        if (rate > state.config.errorRateThreshold) {
            log.warn(
                "CircuitBreaker[{}] CLOSED → OPEN (errorRate={}, threshold={}, samples={}/{})",
                queue, "%.2f".format(rate), state.config.errorRateThreshold, failures, total,
            )
            state.state = State.OPEN
            state.openedAt = now
        }
    }

    private fun evictOld(state: BreakerState, now: Instant) {
        val cutoff = now - state.config.sampleWindow
        while (state.samples.isNotEmpty() && state.samples.first().at < cutoff) {
            state.samples.removeFirst()
        }
    }
}
