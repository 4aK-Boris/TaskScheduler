package cs.trade.scheduler.engine.worker.infrastructure

import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.max
import kotlin.math.min
import kotlin.time.Duration

/**
 * Per-queue adaptive prefetch tuner (DESIGN.md 20.7).
 *
 * Each [SchedulerWorkerConfig.queues] entry that carries an [AdaptivePrefetch] config gets
 * a rolling window of handler-execution durations. On every tick (driven by the
 * [WorkerPool] tuner loop), the tuner computes p95 over the window and proposes a new
 * prefetch:
 *
 *  - **p95 > target × 1.5 → halve prefetch (multiplicative decrease).** Overload signal —
 *    handlers are slow, holding more in-flight just bloats the local pool while other
 *    consumers starve. AIMD-style aggressive backoff.
 *  - **p95 < target × 0.5 → bump prefetch by `max(1, current / 4)` (additive increase).**
 *    Idle signal — handlers are fast, more prefetch lets us amortise broker round-trips.
 *    Additive growth keeps the swing controlled.
 *  - **In the dead band [0.5×target … 1.5×target] → no change.** Avoids whipsawing on
 *    each tick when latency is steady-state near target.
 *
 * Clamped to `[minPrefetch, maxPrefetch]` per the queue's [AdaptivePrefetch] config.
 *
 * Insufficient samples (`< sampleWindowSize / 4`, minimum 5) → no change. p95 over five
 * samples is meaningless; we wait until the window is at least quarter-full before
 * suggesting moves.
 *
 * **Thread safety.** [record] is called from the worker dispatch path and may run on any
 * IO worker thread; [tune] is called from the dedicated tuner-loop coroutine. State per
 * queue is a separate `ArrayDeque` guarded by a single intrinsic monitor lock — contention
 * is minimal (one append per finished job, one snapshot per `tuneInterval`).
 */
public class PrefetchTuner {

    private val log = LoggerFactory.getLogger(javaClass)

    private data class QueueState(
        val config: AdaptivePrefetch,
        val samples: ArrayDeque<Long> = ArrayDeque(),
        var currentPrefetch: Int,
    )

    private val states = ConcurrentHashMap<String, QueueState>()

    /**
     * Register a queue for adaptive tuning. Idempotent; subsequent registrations with the
     * same name overwrite the config (e.g. when the WorkerPool is restarted in tests).
     */
    public fun register(queue: String, config: AdaptivePrefetch, initialPrefetch: Int) {
        require(initialPrefetch in config.minPrefetch..config.maxPrefetch) {
            "initialPrefetch ($initialPrefetch) out of [${config.minPrefetch}, ${config.maxPrefetch}] for queue $queue"
        }
        states[queue] = QueueState(config = config, currentPrefetch = initialPrefetch)
    }

    /**
     * Append a single handler-execution sample. Drops oldest when the window is full.
     * Silently ignored for queues that aren't registered (i.e. queues without an
     * [AdaptivePrefetch] config) — caller doesn't need to gate.
     */
    public fun record(queue: String, duration: Duration) {
        val state = states[queue] ?: return
        val millis = duration.inWholeMilliseconds
        synchronized(state.samples) {
            state.samples.addLast(millis)
            while (state.samples.size > state.config.sampleWindowSize) {
                state.samples.removeFirst()
            }
        }
    }

    /**
     * Decide a new prefetch value, or `null` to keep the current value. Caller (the
     * tuner loop in [WorkerPool]) bridges the result to [cs.trade.scheduler.transport.rabbit.domain.ConsumerHandle.setPrefetch].
     */
    public fun tune(queue: String): Int? {
        val state = states[queue] ?: return null
        val snapshot = synchronized(state.samples) { state.samples.toLongArray() }
        val minSamples = max(5, state.config.sampleWindowSize / 4)
        if (snapshot.size < minSamples) return null

        val p95Millis = computeP95(snapshot)
        val targetMillis = state.config.targetLatency.inWholeMilliseconds
        val proposed = when {
            p95Millis > targetMillis * 3 / 2 -> {
                // Overload — halve. Integer division is fine; we clamp below.
                state.currentPrefetch / 2
            }
            p95Millis < targetMillis / 2 -> {
                // Idle — additive bump, ≥1.
                state.currentPrefetch + max(1, state.currentPrefetch / 4)
            }
            else -> {
                return null
            }
        }
        val clamped = proposed.coerceIn(state.config.minPrefetch, state.config.maxPrefetch)
        if (clamped == state.currentPrefetch) return null
        log.info(
            "Adaptive prefetch — queue={}, p95={}ms, target={}ms, {} -> {} (samples={})",
            queue, p95Millis, targetMillis, state.currentPrefetch, clamped, snapshot.size,
        )
        state.currentPrefetch = clamped
        return clamped
    }

    /** Current prefetch for [queue], or `null` if the queue isn't registered. */
    public fun currentPrefetch(queue: String): Int? = states[queue]?.currentPrefetch

    private fun computeP95(samples: LongArray): Long {
        if (samples.isEmpty()) return 0
        val sorted = samples.copyOf().also { it.sort() }
        // Nearest-rank p95. For n samples, the rank index is ceil(0.95 * n) - 1, clamped
        // to [0, n-1]. n=20 → idx 18; n=100 → idx 94. Avoids interpolation, which doesn't
        // buy us anything over a 100-sample window of integer milliseconds.
        val idx = ((sorted.size * 95 + 99) / 100 - 1).coerceIn(0, sorted.size - 1)
        return sorted[idx]
    }
}
