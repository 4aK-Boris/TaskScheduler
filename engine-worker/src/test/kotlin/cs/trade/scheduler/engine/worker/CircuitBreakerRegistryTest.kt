package cs.trade.scheduler.engine.worker

import cs.trade.scheduler.engine.worker.infrastructure.CircuitBreakerConfig
import cs.trade.scheduler.engine.worker.infrastructure.CircuitBreakerRegistry
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * Unit-level coverage for [CircuitBreakerRegistry]'s state machine. No coroutines — we
 * inject a manual clock so transitions driven by elapsed time are deterministic.
 *
 * The harder integration story (WorkerPool gates pickup on `tryAcquire`, defers via
 * outbox re-publish, records outcomes in the finally block) is covered indirectly by
 * the existing `WorkerIntegrationTest` continuing to pass with no breaker configured.
 */
class CircuitBreakerRegistryTest {

    private val cfg = CircuitBreakerConfig(
        errorRateThreshold = 0.5,
        minSamples = 4,
        sampleWindow = 10.seconds,
        openDuration = 5.seconds,
    )

    /** Mutable clock — bump via `at(ms)` calls. Lets tests drive HALF_OPEN transitions. */
    private class FakeClock(initial: Long = 0L) {
        var now: Instant = Instant.fromEpochMilliseconds(initial)
        fun advance(millis: Long) { now = Instant.fromEpochMilliseconds(now.toEpochMilliseconds() + millis) }
        fun provider(): () -> Instant = { now }
    }

    @Test
    fun `unregistered queue — tryAcquire returns true and record is a no-op`() {
        val reg = CircuitBreakerRegistry()
        assertTrue(reg.tryAcquire("never-registered"))
        reg.record("never-registered", success = false)
        // No state to inspect because the queue was never registered.
        assertNull(reg.stateOf("never-registered"))
    }

    @Test
    fun `CLOSED stays CLOSED below minSamples even with all failures`() {
        val clock = FakeClock()
        val reg = CircuitBreakerRegistry(clock.provider())
        reg.register("q", cfg)

        // 3 failures (< minSamples = 4) — must not trip.
        repeat(3) { reg.record("q", success = false) }
        assertEquals(CircuitBreakerRegistry.State.CLOSED, reg.stateOf("q"))
        assertTrue(reg.tryAcquire("q"))
    }

    @Test
    fun `CLOSED transitions to OPEN when errorRate above threshold and samples sufficient`() {
        val clock = FakeClock()
        val reg = CircuitBreakerRegistry(clock.provider())
        reg.register("q", cfg)

        // 4 samples, 3 fail — errorRate = 0.75 > 0.5 → trip on the 4th record.
        repeat(3) { reg.record("q", success = false); clock.advance(10) }
        assertEquals(CircuitBreakerRegistry.State.CLOSED, reg.stateOf("q"))
        reg.record("q", success = false)
        assertEquals(CircuitBreakerRegistry.State.OPEN, reg.stateOf("q"))
        // OPEN refuses pickup.
        assertFalse(reg.tryAcquire("q"))
    }

    @Test
    fun `CLOSED does NOT trip when errorRate equals threshold (strict greater-than comparison)`() {
        val clock = FakeClock()
        val reg = CircuitBreakerRegistry(clock.provider())
        reg.register("q", cfg)

        // 4 samples, exactly 2 failed → errorRate = 0.5, equals threshold (not >).
        listOf(true, false, true, false).forEach { reg.record("q", it); clock.advance(10) }
        assertEquals(CircuitBreakerRegistry.State.CLOSED, reg.stateOf("q"))
    }

    @Test
    fun `OPEN transitions to HALF_OPEN after openDuration elapses`() {
        val clock = FakeClock()
        val reg = CircuitBreakerRegistry(clock.provider())
        reg.register("q", cfg)

        // Trip first.
        repeat(4) { reg.record("q", success = false) }
        assertEquals(CircuitBreakerRegistry.State.OPEN, reg.stateOf("q"))

        // Just before openDuration — still OPEN.
        clock.advance(cfg.openDuration.inWholeMilliseconds - 1)
        assertFalse(reg.tryAcquire("q"))
        assertEquals(CircuitBreakerRegistry.State.OPEN, reg.stateOf("q"))

        // Exactly at openDuration — transition to HALF_OPEN, first probe goes through.
        clock.advance(1)
        assertTrue(reg.tryAcquire("q"), "first probe should be allowed at openDuration boundary")
        assertEquals(CircuitBreakerRegistry.State.HALF_OPEN, reg.stateOf("q"))
    }

    @Test
    fun `HALF_OPEN allows exactly one in-flight probe — concurrent calls refuse`() {
        val clock = FakeClock()
        val reg = CircuitBreakerRegistry(clock.provider())
        reg.register("q", cfg)

        // Trip + cooldown.
        repeat(4) { reg.record("q", success = false) }
        clock.advance(cfg.openDuration.inWholeMilliseconds + 1)

        // First probe wins.
        assertTrue(reg.tryAcquire("q"))
        // Second concurrent attempt while probe is in-flight is refused.
        assertFalse(reg.tryAcquire("q"))
        assertEquals(CircuitBreakerRegistry.State.HALF_OPEN, reg.stateOf("q"))
    }

    @Test
    fun `releaseProbe hands the slot back without banking a sample`() {
        val clock = FakeClock()
        val reg = CircuitBreakerRegistry(clock.provider())
        reg.register("q", cfg)

        repeat(4) { reg.record("q", success = false) }
        clock.advance(cfg.openDuration.inWholeMilliseconds + 1)
        assertTrue(reg.tryAcquire("q"))
        assertFalse(reg.tryAcquire("q"))

        // A cancelled job says nothing about downstream health: no sample, but the slot
        // must come back or the queue is stuck refusing everything.
        reg.releaseProbe("q")
        assertEquals(CircuitBreakerRegistry.State.HALF_OPEN, reg.stateOf("q"))
        assertTrue(reg.tryAcquire("q"), "slot must be free for the next candidate")
    }

    @Test
    fun `releaseProbe is a no-op for CLOSED, OPEN and unregistered queues`() {
        val clock = FakeClock()
        val reg = CircuitBreakerRegistry(clock.provider())
        reg.register("q", cfg)

        // CLOSED — nothing to release, state untouched.
        reg.releaseProbe("q")
        assertEquals(CircuitBreakerRegistry.State.CLOSED, reg.stateOf("q"))

        // OPEN — a late release from the probe that just failed must not re-admit traffic.
        repeat(4) { reg.record("q", success = false) }
        assertEquals(CircuitBreakerRegistry.State.OPEN, reg.stateOf("q"))
        reg.releaseProbe("q")
        assertEquals(CircuitBreakerRegistry.State.OPEN, reg.stateOf("q"))
        assertFalse(reg.tryAcquire("q"))

        reg.releaseProbe("never-registered")
    }

    @Test
    fun `HALF_OPEN probe that never reports an outcome expires after probeTimeout`() {
        val shortProbe = cfg.copy(probeTimeout = 30.seconds)
        val clock = FakeClock()
        val reg = CircuitBreakerRegistry(clock.provider())
        reg.register("q", shortProbe)

        repeat(4) { reg.record("q", success = false) }
        clock.advance(shortProbe.openDuration.inWholeMilliseconds + 1)
        assertTrue(reg.tryAcquire("q"))

        // The probe's worker was force-killed mid-handler: no record(), no releaseProbe().
        // Until the deadline passes we keep refusing — that's the point of one probe.
        clock.advance(shortProbe.probeTimeout.inWholeMilliseconds - 1)
        assertFalse(reg.tryAcquire("q"))

        // Past it, a fresh probe goes through instead of the queue wedging forever.
        clock.advance(1)
        assertTrue(reg.tryAcquire("q"), "an abandoned probe must not hold the slot for good")
        assertEquals(CircuitBreakerRegistry.State.HALF_OPEN, reg.stateOf("q"))

        // And the replacement gets its own full deadline.
        assertFalse(reg.tryAcquire("q"))
    }

    @Test
    fun `HALF_OPEN probe success closes breaker and clears window`() {
        val clock = FakeClock()
        val reg = CircuitBreakerRegistry(clock.provider())
        reg.register("q", cfg)

        repeat(4) { reg.record("q", success = false) }
        clock.advance(cfg.openDuration.inWholeMilliseconds + 1)
        assertTrue(reg.tryAcquire("q"))

        // Probe reports success → CLOSED, window cleared.
        reg.record("q", success = true)
        assertEquals(CircuitBreakerRegistry.State.CLOSED, reg.stateOf("q"))
        assertTrue(reg.tryAcquire("q"))

        // Even one more failure doesn't trip because the window was cleared on close.
        reg.record("q", success = false)
        assertEquals(CircuitBreakerRegistry.State.CLOSED, reg.stateOf("q"))
    }

    @Test
    fun `HALF_OPEN probe failure re-opens for another cooldown cycle`() {
        val clock = FakeClock()
        val reg = CircuitBreakerRegistry(clock.provider())
        reg.register("q", cfg)

        repeat(4) { reg.record("q", success = false) }
        clock.advance(cfg.openDuration.inWholeMilliseconds + 1)
        assertTrue(reg.tryAcquire("q"))

        // Probe fails → back to OPEN.
        reg.record("q", success = false)
        assertEquals(CircuitBreakerRegistry.State.OPEN, reg.stateOf("q"))
        assertFalse(reg.tryAcquire("q"))

        // Another full openDuration must elapse before the next probe.
        clock.advance(cfg.openDuration.inWholeMilliseconds - 1)
        assertFalse(reg.tryAcquire("q"))
        clock.advance(1)
        assertTrue(reg.tryAcquire("q"))
        assertEquals(CircuitBreakerRegistry.State.HALF_OPEN, reg.stateOf("q"))
    }

    @Test
    fun `old samples age out of the rolling window`() {
        val shortWindow = cfg.copy(sampleWindow = 100.milliseconds)
        val clock = FakeClock()
        val reg = CircuitBreakerRegistry(clock.provider())
        reg.register("q", shortWindow)

        // 4 failures in quick succession — would trip if all in window.
        repeat(3) { reg.record("q", success = false); clock.advance(10) }
        assertEquals(CircuitBreakerRegistry.State.CLOSED, reg.stateOf("q"))

        // Wait for samples to age out — then add one more failure. The 3 previous fall
        // outside the 100ms window, so we only have 1 sample again (< minSamples).
        clock.advance(200)
        reg.record("q", success = false)
        assertEquals(
            CircuitBreakerRegistry.State.CLOSED,
            reg.stateOf("q"),
            "old samples must age out so a brief past failure doesn't haunt the breaker forever",
        )
    }

    @Test
    fun `re-registering replaces state`() {
        val clock = FakeClock()
        val reg = CircuitBreakerRegistry(clock.provider())
        reg.register("q", cfg)
        repeat(4) { reg.record("q", success = false) }
        assertEquals(CircuitBreakerRegistry.State.OPEN, reg.stateOf("q"))

        // Re-register with the same config — state resets to CLOSED, samples cleared.
        reg.register("q", cfg)
        assertEquals(CircuitBreakerRegistry.State.CLOSED, reg.stateOf("q"))
        assertTrue(reg.tryAcquire("q"))
    }
}
