package cs.trade.scheduler.core.backend.context

import kotlinx.serialization.Serializable

/**
 * Captured cross-cutting context, serialised into `job.context_json` at enqueue time
 * and re-applied at execute time by [ContextRestore]. Shape matches DESIGN.md 22.11.
 *
 * `mdc` carries the SLF4J Mapped Diagnostic Context (already filtered by an allowlist
 * in [ContextCapture]). `traceparent`/`tracestate` are the W3C trace-context headers —
 * just strings here because :core:backend depends on `opentelemetry-api`, not the SDK,
 * so we serialize the wire format and rebuild the Span on the worker side.
 *
 * All fields nullable / default-empty so an enqueue with no context still produces a
 * compact JSON (`{}`) — or, more usually, we skip the column entirely when nothing
 * was captured (see [isEmpty]).
 */
@Serializable
public data class ContextSnapshot(
    val mdc: Map<String, String> = emptyMap(),
    val traceparent: String? = null,
    val tracestate: String? = null,
) {
    public fun isEmpty(): Boolean = mdc.isEmpty() && traceparent == null && tracestate == null
}
