package cs.trade.scheduler.engine.worker.infrastructure.metrics

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
) {
    private val log = LoggerFactory.getLogger(javaClass)

    public fun bind() {
        val queues = config.queueNames
        queues.forEach { queueName ->
            Gauge.builder(METRIC_NAME) { inFlight.current(queueName).toDouble() }
                .description("Jobs picked up but not yet finalized on this worker, per queue")
                .tag("queue", queueName)
                .tag("node", config.nodeId)
                .register(registry)
        }
        log.info(
            "WorkerMetricsBinder registered {} in-flight gauge(s) for node={} queues={}",
            queues.size, config.nodeId, queues,
        )
    }

    public companion object {
        public const val METRIC_NAME: String = "scheduler.worker.in_flight"
    }
}
