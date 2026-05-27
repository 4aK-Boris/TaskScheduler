package cs.trade.scheduler.engine.worker

import cs.trade.scheduler.engine.worker.infrastructure.CircuitBreakerConfig
import cs.trade.scheduler.engine.worker.infrastructure.CircuitBreakerRegistry
import cs.trade.scheduler.engine.worker.infrastructure.SchedulerWorkerConfig
import cs.trade.scheduler.engine.worker.infrastructure.WorkerInFlightCounter
import cs.trade.scheduler.engine.worker.infrastructure.metrics.JobMetrics
import cs.trade.scheduler.engine.worker.infrastructure.metrics.JobOutcome
import cs.trade.scheduler.engine.worker.infrastructure.metrics.MicrometerJobMetrics
import cs.trade.scheduler.engine.worker.infrastructure.metrics.WorkerMetricsBinder
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Unit-level coverage for the Micrometer bindings (DESIGN.md 22.5). Uses
 * `SimpleMeterRegistry` so assertions can read counter / timer / gauge values directly
 * without parsing scraped Prometheus output.
 *
 * What's tested here:
 *  - [MicrometerJobMetrics.recordRetry] emits `scheduler.retry.total{queue, payload_type}`
 *  - [MicrometerJobMetrics.recordExecution] emits `scheduler.job.execution` Timer
 *  - [WorkerMetricsBinder] registers an in-flight gauge per queue + a CB-state gauge per
 *    queue that has a `circuitBreaker` config (and skips for queues without one)
 *  - CB-state gauge reads from [CircuitBreakerRegistry] at scrape time and reflects the
 *    current state numerically (0 CLOSED, 1 HALF_OPEN, 2 OPEN)
 */
class MetricsBindingTest {

    @Test
    fun `recordRetry emits scheduler_retry_total counter tagged by queue and payload_type`() {
        val registry = SimpleMeterRegistry()
        val metrics: JobMetrics = MicrometerJobMetrics(registry)

        metrics.recordRetry("default", "com.example.SendEmail")
        metrics.recordRetry("default", "com.example.SendEmail")
        metrics.recordRetry("heavy", "com.example.Report")

        val sendEmail = registry.find(MicrometerJobMetrics.RETRY_METRIC_NAME)
            .tag("queue", "default")
            .tag("payload_type", "com.example.SendEmail")
            .counter()
        assertNotNull(sendEmail, "counter for (default, SendEmail) must exist")
        assertEquals(2.0, sendEmail!!.count(), "two recordRetry calls -> count=2")

        val report = registry.find(MicrometerJobMetrics.RETRY_METRIC_NAME)
            .tag("queue", "heavy")
            .tag("payload_type", "com.example.Report")
            .counter()
        assertEquals(1.0, report?.count())
    }

    @Test
    fun `recordExecution emits scheduler_job_execution timer tagged by queue, payload_type, outcome`() {
        val registry = SimpleMeterRegistry()
        val metrics: JobMetrics = MicrometerJobMetrics(registry)

        metrics.recordExecution("default", "T", JobOutcome.SUCCESS, 100.milliseconds)
        metrics.recordExecution("default", "T", JobOutcome.SUCCESS, 200.milliseconds)
        metrics.recordExecution("default", "T", JobOutcome.FAILED, 50.milliseconds)

        val succ = registry.find(MicrometerJobMetrics.METRIC_NAME)
            .tag("queue", "default")
            .tag("payload_type", "T")
            .tag("outcome", JobOutcome.SUCCESS.tagValue)
            .timer()
        assertNotNull(succ)
        assertEquals(2L, succ!!.count(), "two SUCCESS samples")

        val failed = registry.find(MicrometerJobMetrics.METRIC_NAME)
            .tag("queue", "default")
            .tag("payload_type", "T")
            .tag("outcome", JobOutcome.FAILED.tagValue)
            .timer()
        assertEquals(1L, failed?.count())
    }

    @Test
    fun `WorkerMetricsBinder registers an in-flight gauge per declared queue`() {
        val registry = SimpleMeterRegistry()
        val inFlight = WorkerInFlightCounter()
        val cbRegistry = CircuitBreakerRegistry()
        val config = SchedulerWorkerConfig().apply {
            nodeId = "node-a"
            queue("default", concurrency = 4)
            queue("email", concurrency = 8)
        }
        val binder = WorkerMetricsBinder(registry, inFlight, config, cbRegistry)
        binder.bind()

        inFlight.increment("default")
        inFlight.increment("default")
        inFlight.increment("email")

        val def = registry.find(WorkerMetricsBinder.IN_FLIGHT_METRIC_NAME)
            .tag("queue", "default").tag("node", "node-a").gauge()
        assertEquals(2.0, def?.value())

        val email = registry.find(WorkerMetricsBinder.IN_FLIGHT_METRIC_NAME)
            .tag("queue", "email").tag("node", "node-a").gauge()
        assertEquals(1.0, email?.value())
    }

    @Test
    fun `WorkerMetricsBinder skips circuit-breaker gauge for queues without a CB config`() {
        val registry = SimpleMeterRegistry()
        val inFlight = WorkerInFlightCounter()
        val cbRegistry = CircuitBreakerRegistry()
        val config = SchedulerWorkerConfig().apply {
            queue("plain", concurrency = 1)
        }
        WorkerMetricsBinder(registry, inFlight, config, cbRegistry).bind()

        val gauge = registry.find(WorkerMetricsBinder.CB_STATE_METRIC_NAME)
            .tag("queue", "plain").gauge()
        assertNull(gauge, "no CB config -> no CB-state gauge (avoids meaningless always-zero series)")
    }

    @Test
    fun `WorkerMetricsBinder CB-state gauge reflects registry state numerically`() {
        val registry = SimpleMeterRegistry()
        val inFlight = WorkerInFlightCounter()
        val cbRegistry = CircuitBreakerRegistry()
        val cbConfig = CircuitBreakerConfig(
            errorRateThreshold = 0.5,
            minSamples = 4,
            sampleWindow = 10.seconds,
            openDuration = 5.seconds,
        )
        val config = SchedulerWorkerConfig().apply {
            queue("flaky", concurrency = 2, circuitBreaker = cbConfig)
        }
        cbRegistry.register("flaky", cbConfig)
        WorkerMetricsBinder(registry, inFlight, config, cbRegistry).bind()

        val gauge = registry.find(WorkerMetricsBinder.CB_STATE_METRIC_NAME)
            .tag("queue", "flaky").gauge()
        assertNotNull(gauge, "queue with CB config must have a CB-state gauge")
        // Fresh state = CLOSED = 0.
        assertEquals(0.0, gauge!!.value())

        // Trip the breaker — 4 failures, rate=1.0 > threshold=0.5 -> OPEN.
        repeat(4) { cbRegistry.record("flaky", success = false) }
        assertEquals(2.0, gauge.value(), "OPEN encodes to 2")
    }
}
