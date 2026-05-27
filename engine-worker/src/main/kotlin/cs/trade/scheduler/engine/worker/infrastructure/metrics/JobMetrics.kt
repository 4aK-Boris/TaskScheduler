package cs.trade.scheduler.engine.worker.infrastructure.metrics

import kotlin.time.Duration

/**
 * Sink for per-execution job metrics emitted by [cs.trade.scheduler.engine.worker.infrastructure.WorkerPool].
 *
 * The interface is intentionally minimal so the engine-worker module can stay free of
 * Micrometer at the API layer — only [MicrometerJobMetrics] pulls in micrometer-core.
 * User-apps that don't wire a [io.micrometer.core.instrument.MeterRegistry] keep the
 * [Noop] singleton bound by [cs.trade.scheduler.engine.worker.infrastructure.schedulerWorkerModule]
 * and pay zero overhead.
 *
 * Implementations MUST be cheap and non-blocking — they're called from the hot per-job
 * path. Bucketing / aggregation should happen inside the implementation (Micrometer
 * Timers are lock-free).
 */
public interface JobMetrics {
    /**
     * Records one terminal handler invocation. `duration` reflects only the time spent
     * inside `handler.execute` (and the OTel/MDC context restore wrapper) — DB
     * finalize calls and outbox publishes are deliberately excluded so the metric
     * matches the user's intuition of "how long did my job actually run".
     */
    public fun recordExecution(
        queue: String,
        payloadType: String,
        outcome: JobOutcome,
        duration: Duration,
    )

    /**
     * Records a retry being scheduled (DESIGN.md 22.5). Called by
     * [cs.trade.scheduler.engine.worker.infrastructure.WorkerPool] when a handler fails
     * AND the retry policy allows another attempt — NOT when the job terminally FAILs.
     *
     * The `scheduler_retry_total` counter this drives is the load-bearing signal for
     * "is some downstream getting flaky?" — Grafana panels typically render `rate(...)`
     * over a window.
     */
    public fun recordRetry(queue: String, payloadType: String) { /* default no-op for binary compat with custom impls */ }

    public object Noop : JobMetrics {
        override fun recordExecution(
            queue: String,
            payloadType: String,
            outcome: JobOutcome,
            duration: Duration,
        ) {
            // intentionally empty
        }
    }
}
