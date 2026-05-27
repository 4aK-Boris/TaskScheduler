package cs.trade.scheduler.runner

import cs.trade.scheduler.core.backend.idempotency.IdempotencyMetrics
import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.MeterRegistry
import java.util.concurrent.ConcurrentHashMap

/**
 * Micrometer-backed [IdempotencyMetrics]. Emits `scheduler.idempotency.dedup.total{action}`
 * each time `tryMark(...)` returns false (PK conflict — handler already executed for
 * the `(jobId, action)` pair). See DESIGN.md 22.5.
 *
 * Per-action Counters are cached in a [ConcurrentHashMap] — Micrometer's registry lookup
 * is synchronized, and the hot dedup path on a high-replay queue would contend on it.
 *
 * Lives in `:standalone-runner` rather than `:storage-postgres` because pulling Micrometer
 * into storage just to register a counter would leak observability infra into the storage
 * layer. The runner is the natural composition root for "we have a registry, here's how
 * to wire it" decisions.
 */
public class MicrometerIdempotencyMetrics(
    private val registry: MeterRegistry,
) : IdempotencyMetrics {

    private val counters = ConcurrentHashMap<String, Counter>()

    override fun recordDedup(action: String) {
        val counter = counters.computeIfAbsent(action) { a ->
            Counter.builder(METRIC_NAME)
                .description("IdempotencyStore.tryMark returned false — duplicate delivery suppressed by the PK on (jobId, action)")
                .tag("action", a)
                .register(registry)
        }
        counter.increment()
    }

    public companion object {
        public const val METRIC_NAME: String = "scheduler.idempotency.dedup.total"
    }
}
