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
 *    cycle. A probe that reports no outcome at all (cancelled job — see [releaseProbe])
 *    just hands the slot back, leaving the breaker in HALF_OPEN for the next candidate.
 *
 * The probe slot is the one piece of state that can strand the whole queue: while it's
 * taken every pickup is refused, so nothing is left to close the breaker or to re-open
 * it. Two things guard it — the worker returns it in a `finally` on every exit path, and
 * [CircuitBreakerConfig.probeTimeout] expires it for the case where that `finally` never
 * runs (a force-cancelled handler whose coroutine outlives the delivery).
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
        // When the in-flight HALF_OPEN probe was handed out; null = slot free. Doubles as
        // the "probe in flight" flag and as the deadline base for probeTimeout.
        @Volatile var probeStartedAt: Instant? = null,
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
     * value of `true` takes the probe slot, so subsequent calls return `false` until the
     * probe reports its outcome via [record], hands the slot back via [releaseProbe], or
     * runs past [CircuitBreakerConfig.probeTimeout].
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
                    val now = clock()
                    val startedAt = state.probeStartedAt
                    val expired = startedAt != null && now - startedAt >= state.config.probeTimeout
                    when {
                        startedAt != null && !expired -> false
                        else -> {
                            if (expired) {
                                // The previous probe never reported back — its worker was
                                // force-killed mid-handler, so nothing will ever call
                                // record()/releaseProbe() for it. Hand the slot to this
                                // pickup instead of refusing every job until restart.
                                log.warn(
                                    "CircuitBreaker[{}] HALF_OPEN probe exceeded {} without an outcome — issuing a new probe",
                                    queue, state.config.probeTimeout,
                                )
                            }
                            state.probeStartedAt = now
                            true
                        }
                    }
                }
            }
        }
    }

    /**
     * Hand the probe slot back without banking a sample. For outcomes that say nothing
     * about downstream health (a cancelled job — operator action, not a sick dependency)
     * and for the pre-handler exits that never reach an outcome at all: no registered
     * handler, an undecodable payload, a cancel that landed while the message sat in
     * Rabbit.
     *
     * No-op unless this queue is HALF_OPEN with a probe out, so a late call can't clobber
     * a slot that already moved on. Tolerates unregistered queues.
     */
    public fun releaseProbe(queue: String) {
        val state = breakers[queue] ?: return
        synchronized(state) {
            if (state.state == State.HALF_OPEN && state.probeStartedAt != null) {
                state.probeStartedAt = null
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
                    state.probeStartedAt = null
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
            state.probeStartedAt = null
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
