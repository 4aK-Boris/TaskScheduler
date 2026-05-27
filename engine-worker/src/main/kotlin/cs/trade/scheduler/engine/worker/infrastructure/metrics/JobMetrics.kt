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
