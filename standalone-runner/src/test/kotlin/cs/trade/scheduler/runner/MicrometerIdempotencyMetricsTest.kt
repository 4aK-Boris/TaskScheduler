package cs.trade.scheduler.runner

import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test

/**
 * Unit coverage for [MicrometerIdempotencyMetrics]'s counter wiring. The end-to-end
 * "tryMark on a duplicate fires this counter" path is implicitly covered by any
 * `EnqueueOnceIntegrationTest` that already exercises PG insert + dedup; here we just
 * pin the Micrometer surface so a refactor of the metric name / tag scheme triggers a
 * test failure rather than a silent dashboard regression.
 */
class MicrometerIdempotencyMetricsTest {

    @Test
    fun `recordDedup emits scheduler_idempotency_dedup_total tagged by action`() {
        val registry = SimpleMeterRegistry()
        val metrics = MicrometerIdempotencyMetrics(registry)

        metrics.recordDedup("charge")
        metrics.recordDedup("charge")
        metrics.recordDedup("notify")

        val charge = registry.find(MicrometerIdempotencyMetrics.METRIC_NAME)
            .tag("action", "charge").counter()
        assertNotNull(charge, "counter for action=charge must exist")
        assertEquals(2.0, charge!!.count())

        val notify = registry.find(MicrometerIdempotencyMetrics.METRIC_NAME)
            .tag("action", "notify").counter()
        assertEquals(1.0, notify?.count())
    }

    @Test
    fun `repeated actions reuse the same Counter (no per-call registry lookup)`() {
        val registry = SimpleMeterRegistry()
        val metrics = MicrometerIdempotencyMetrics(registry)

        repeat(100) { metrics.recordDedup("hot-action") }
        val counter = registry.find(MicrometerIdempotencyMetrics.METRIC_NAME)
            .tag("action", "hot-action").counter()
        assertEquals(100.0, counter?.count())

        // No second counter spawned — same (name, tag) hits the cache.
        val allWithSameName = registry.find(MicrometerIdempotencyMetrics.METRIC_NAME).counters()
        assertEquals(1, allWithSameName.size, "the registry must hold exactly one counter for action=hot-action")
    }
}
