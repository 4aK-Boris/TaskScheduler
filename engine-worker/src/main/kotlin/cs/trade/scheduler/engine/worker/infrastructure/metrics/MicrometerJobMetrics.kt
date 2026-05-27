package cs.trade.scheduler.engine.worker.infrastructure.metrics

import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration
import kotlin.time.toJavaDuration

/**
 * Micrometer-backed [JobMetrics]. Records to a single Timer family
 * `scheduler.job.execution` (Prometheus: `scheduler_job_execution_seconds`) tagged by
 * `queue`, `payload_type`, and `outcome`.
 *
 * Per-tag-combo Timers are cached in a ConcurrentHashMap — Micrometer's own registry
 * lookup is synchronized, and the hot per-job path can't afford a global lock.
 *
 * Histogram buckets are configured via [publishPercentileHistogram] so percentile
 * aggregation works across replicas at scrape time (Prometheus `histogram_quantile`).
 */
public class MicrometerJobMetrics(
    private val registry: MeterRegistry,
) : JobMetrics {

    private val timers = ConcurrentHashMap<TimerKey, Timer>()
    private val retryCounters = ConcurrentHashMap<CounterKey, Counter>()

    override fun recordExecution(
        queue: String,
        payloadType: String,
        outcome: JobOutcome,
        duration: Duration,
    ) {
        val key = TimerKey(queue, payloadType, outcome)
        val timer = timers.computeIfAbsent(key) { k ->
            Timer.builder(METRIC_NAME)
                .description("Time spent inside JobHandler.execute, by queue/payload/outcome")
                .tag("queue", k.queue)
                .tag("payload_type", k.payloadType)
                .tag("outcome", k.outcome.tagValue)
                .publishPercentileHistogram()
                .register(registry)
        }
        timer.record(duration.toJavaDuration())
    }

    override fun recordRetry(queue: String, payloadType: String) {
        val key = CounterKey(queue, payloadType)
        val counter = retryCounters.computeIfAbsent(key) { k ->
            Counter.builder(RETRY_METRIC_NAME)
                .description("Retries scheduled by the retry policy — fed from WorkerPool.handleFailure")
                .tag("queue", k.queue)
                .tag("payload_type", k.payloadType)
                .register(registry)
        }
        counter.increment()
    }

    private data class TimerKey(
        val queue: String,
        val payloadType: String,
        val outcome: JobOutcome,
    )

    private data class CounterKey(
        val queue: String,
        val payloadType: String,
    )

    public companion object {
        public const val METRIC_NAME: String = "scheduler.job.execution"
        public const val RETRY_METRIC_NAME: String = "scheduler.retry.total"
    }
}
