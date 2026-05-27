package cs.trade.scheduler.core.backend.idempotency

/**
 * Sink for idempotency-related Prometheus counters (DESIGN.md 22.5). Decoupled from
 * Micrometer at the API layer — only a downstream impl (e.g.
 * `MicrometerIdempotencyMetrics` in `:standalone-runner`) pulls in micrometer-core.
 *
 * Implementations MUST be cheap and non-blocking. Called from
 * [cs.trade.scheduler.storage.postgres.infrastructure.scheduler.PostgresIdempotencyStore.tryMark]
 * (and any future store impl) on every dedup hit, which on a hot retry / re-delivery
 * loop can fire often.
 *
 * The default [Noop] binding paid-for-by users who don't wire a [io.micrometer.core.instrument.MeterRegistry]
 * — keeps the engine-worker module free of observability deps when not desired.
 */
public interface IdempotencyMetrics {

    /**
     * Records one `tryMark(...)` call that returned `false` (PK conflict — handler
     * already executed for this `(jobId, action)` pair). The `action` is the same one
     * the handler passed; useful for slicing dedup-rate by sub-step in multi-step jobs.
     *
     * Implementations should emit a counter named `scheduler.idempotency.dedup.total`
     * tagged by `action`. Consumers' `rate(...)` over a window measures
     * how often re-delivery (or genuine duplicate enqueue) actually catches.
     */
    public fun recordDedup(action: String)

    public object Noop : IdempotencyMetrics {
        override fun recordDedup(action: String): Unit = Unit
    }
}
