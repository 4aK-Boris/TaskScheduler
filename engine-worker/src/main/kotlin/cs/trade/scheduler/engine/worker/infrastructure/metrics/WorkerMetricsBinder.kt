package cs.trade.scheduler.engine.worker.infrastructure.metrics

import cs.trade.scheduler.engine.worker.infrastructure.CircuitBreakerRegistry
import cs.trade.scheduler.engine.worker.infrastructure.SchedulerWorkerConfig
import cs.trade.scheduler.engine.worker.infrastructure.WorkerInFlightCounter
import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MeterRegistry
import org.slf4j.LoggerFactory

/**
 * Registers a per-queue Prometheus gauge family `scheduler.worker.in_flight` driven by
 * [WorkerInFlightCounter]. Each declared queue gets its own gauge tagged with `queue`
 * + `node`, so Grafana queries like
 *
 *     sum by (queue) (scheduler_worker_in_flight)
 *
 * give a fleet-wide view of how loaded each queue is.
 *
 * **Wiring (user-app side, requires a [MeterRegistry] in the Koin graph):**
 * ```
 * modules(
 *     schedulerWorkerModule { queue("default"); queue("heavy") },
 *     module {
 *         single<MeterRegistry> { myPrometheusRegistry }
 *         single { WorkerMetricsBinder(get(), get(), get()).also { it.bind() } }
 *     }
 * )
 * koin.get<WorkerMetricsBinder>()   // forces eager instantiation → gauges registered
 * ```
 *
 * Gauges read [WorkerInFlightCounter.current] on every scrape — cheap (AtomicInteger.get())
 * so we don't cache. Re-registering the binder is a no-op as far as Micrometer is concerned
 * (same name+tags → same Meter).
 *
 * **Not auto-bound in `schedulerWorkerModule`** — most user-apps don't bring a MeterRegistry,
 * and we'd rather not pull users into observability infra they don't want. The same opt-in
 * pattern applies to [MicrometerJobMetrics] (the histogram impl of [JobMetrics]).
 */
public class WorkerMetricsBinder(
    private val registry: MeterRegistry,
    private val inFlight: WorkerInFlightCounter,
    private val config: SchedulerWorkerConfig,
    private val circuitBreakers: CircuitBreakerRegistry,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    public fun bind() {
        val queues = config.queues
        queues.forEach { q ->
            Gauge.builder(IN_FLIGHT_METRIC_NAME) { inFlight.current(q.name).toDouble() }
                .description("Jobs picked up but not yet finalized on this worker, per queue")
                .tag("queue", q.name)
                .tag("node", config.nodeId)
                .register(registry)

            // Circuit breaker state gauge — emits 0/1/2 per the encoded state. Only
            // registered for queues that actually have a CB config; queues without one
            // don't get a meaningless "always CLOSED" gauge cluttering Grafana.
            if (q.circuitBreaker != null) {
                Gauge.builder(CB_STATE_METRIC_NAME) { encodeState(q.name).toDouble() }
                    .description("Circuit breaker state per queue: 0=CLOSED, 1=HALF_OPEN, 2=OPEN")
                    .tag("queue", q.name)
                    .tag("node", config.nodeId)
                    .register(registry)
            }
        }
        log.info(
            "WorkerMetricsBinder registered {} in-flight gauge(s) for node={} queues={}; cb-gauges={}",
            queues.size, config.nodeId, queues.map { it.name },
            queues.count { it.circuitBreaker != null },
        )
    }

    /** Map CB state to a numeric gauge value. Unregistered queues read 0 (treat as closed). */
    private fun encodeState(queue: String): Int = when (circuitBreakers.stateOf(queue)) {
        CircuitBreakerRegistry.State.CLOSED, null -> 0
        CircuitBreakerRegistry.State.HALF_OPEN -> 1
        CircuitBreakerRegistry.State.OPEN -> 2
    }

    public companion object {
        public const val IN_FLIGHT_METRIC_NAME: String = "scheduler.worker.in_flight"
        public const val CB_STATE_METRIC_NAME: String = "scheduler.circuit_breaker.state"

        // Kept for callers that imported the old name. Will go away in a later cleanup.
        @Deprecated("Use IN_FLIGHT_METRIC_NAME", ReplaceWith("IN_FLIGHT_METRIC_NAME"))
        public const val METRIC_NAME: String = IN_FLIGHT_METRIC_NAME
    }
}
