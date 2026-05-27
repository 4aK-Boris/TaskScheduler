package cs.trade.scheduler.engine.worker.infrastructure.metrics

// Terminal outcome of a single handler invocation — drives the `outcome` Prometheus
// tag on the job-duration histogram. Cardinality is fixed at 4, safe to use as a label.
public enum class JobOutcome(public val tagValue: String) {
    SUCCESS("success"),
    FAILED("failed"),
    RETRIED("retried"),
    CANCELLED("cancelled"),
}
