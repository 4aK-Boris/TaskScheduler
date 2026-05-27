package cs.trade.scheduler.engine.worker

import cs.trade.scheduler.engine.worker.infrastructure.AdaptivePrefetch
import cs.trade.scheduler.engine.worker.infrastructure.PrefetchTuner
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Unit-level coverage for [PrefetchTuner]'s decision function. No coroutines, no
 * transport — just a deterministic feed of samples and assertions on what `tune()`
 * proposes. The wider integration story (loop calls handle.setPrefetch on a real
 * consumer) is covered indirectly by the existing WorkerIntegrationTest's continued
 * green state — that one doesn't register adaptive queues so it must keep passing
 * unchanged.
 */
class PrefetchTunerTest {

    private val config = AdaptivePrefetch(
        targetLatency = 1.seconds,
        minPrefetch = 4,
        maxPrefetch = 64,
        sampleWindowSize = 100,
    )

    @Test
    fun `no samples — tune returns null (insufficient signal)`() {
        val tuner = PrefetchTuner().apply { register("q", config, initialPrefetch = 16) }
        assertNull(tuner.tune("q"))
    }

    @Test
    fun `fewer than minSamples — tune returns null even if those samples are loud`() {
        val tuner = PrefetchTuner().apply { register("q", config, initialPrefetch = 16) }
        // Window is 100, minSamples is 100/4=25. Feed 10 deliberately-bad samples.
        repeat(10) { tuner.record("q", 10.seconds) }
        assertNull(tuner.tune("q"), "10 samples (< 25) must not be enough to move prefetch")
    }

    @Test
    fun `p95 above 1_5x target — halves prefetch (multiplicative decrease)`() {
        val tuner = PrefetchTuner().apply { register("q", config, initialPrefetch = 16) }
        // All samples at 2s = 2× target → p95 = 2s = 2× target, definitely above 1.5×.
        repeat(50) { tuner.record("q", 2.seconds) }
        assertEquals(8, tuner.tune("q"), "16 -> 8 on overload")
        assertEquals(8, tuner.currentPrefetch("q"))
    }

    @Test
    fun `p95 below 0_5x target — additive bump (current + max(1, current_4))`() {
        val tuner = PrefetchTuner().apply { register("q", config, initialPrefetch = 16) }
        // Tight loop: each handler took 200ms — p95 = 200ms = 0.2× target, below 0.5×.
        repeat(50) { tuner.record("q", 200.milliseconds) }
        // 16 + max(1, 16/4) = 16 + 4 = 20.
        assertEquals(20, tuner.tune("q"))
        assertEquals(20, tuner.currentPrefetch("q"))
    }

    @Test
    fun `p95 inside dead band — tune returns null`() {
        val tuner = PrefetchTuner().apply { register("q", config, initialPrefetch = 16) }
        // 900ms = 0.9× target; sits inside [0.5×, 1.5×] dead band.
        repeat(50) { tuner.record("q", 900.milliseconds) }
        assertNull(tuner.tune("q"), "dead-band latency must not trigger a move")
        assertEquals(16, tuner.currentPrefetch("q"), "prefetch left at initial value")
    }

    @Test
    fun `clamp to minPrefetch — halving below the floor returns minPrefetch`() {
        val tuner = PrefetchTuner().apply { register("q", config, initialPrefetch = config.minPrefetch) }
        repeat(50) { tuner.record("q", 5.seconds) }
        // 4 / 2 = 2, but minPrefetch is 4 → clamped, equals current, returns null.
        assertNull(tuner.tune("q"), "already at min — no further decrease, returns null")
        assertEquals(config.minPrefetch, tuner.currentPrefetch("q"))
    }

    @Test
    fun `clamp to maxPrefetch — additive bump above the ceiling returns maxPrefetch`() {
        val tuner = PrefetchTuner().apply { register("q", config, initialPrefetch = config.maxPrefetch) }
        repeat(50) { tuner.record("q", 100.milliseconds) }
        // 64 + 16 = 80, clamped to maxPrefetch=64 → equals current, returns null.
        assertNull(tuner.tune("q"), "already at max — no further increase, returns null")
        assertEquals(config.maxPrefetch, tuner.currentPrefetch("q"))
    }

    @Test
    fun `rolling window — old samples drop off after sampleWindowSize`() {
        val smallCfg = config.copy(sampleWindowSize = 20)
        val tuner = PrefetchTuner().apply { register("q", smallCfg, initialPrefetch = 16) }
        // Fill window with overload samples — should propose halving.
        repeat(20) { tuner.record("q", 3.seconds) }
        assertEquals(8, tuner.tune("q"), "20 slow samples — halve")

        // Now fill the window with fresh fast samples. Old slow ones drop off the head.
        repeat(20) { tuner.record("q", 200.milliseconds) }
        // current=8, +max(1, 8/4)=8+2=10. Should propose 10.
        assertEquals(10, tuner.tune("q"), "after rolling out the slow samples, additive bump kicks in")
    }

    @Test
    fun `unregistered queue — record + tune are no-ops`() {
        val tuner = PrefetchTuner()
        // record on a queue we never registered must not throw and must not affect tune.
        tuner.record("never-registered", 99.seconds)
        assertNull(tuner.tune("never-registered"))
        assertNull(tuner.currentPrefetch("never-registered"))
    }

    @Test
    fun `register validates initialPrefetch is inside the configured bounds`() {
        val tuner = PrefetchTuner()
        val ex = runCatching { tuner.register("q", config, initialPrefetch = 100) }.exceptionOrNull()
        assertTrue(ex is IllegalArgumentException, "out-of-range initialPrefetch must throw IAE; got $ex")
    }
}
