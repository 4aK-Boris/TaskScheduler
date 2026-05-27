package cs.trade.scheduler.core.backend.context

import cs.trade.scheduler.core.backend.ContextPropagationConfig
import io.opentelemetry.api.trace.Span

/**
 * Snapshots the current execution context for cross-process propagation. Called from
 * the enqueue path (when [cs.trade.scheduler.core.backend.EnqueueOptions.captureContext]
 * is true) so the worker can later re-apply the same MDC + parent span when running
 * the handler. See DESIGN.md 22.11.
 *
 * Returns `null` when nothing meaningful was captured — DefaultScheduler then writes
 * NULL into `job.context_json` instead of an empty `{}` payload (smaller rows; SAFETY-
 * NET / dashboard queries can `IS NOT NULL` to identify context-bearing jobs).
 *
 * Depends only on `opentelemetry-api`. When the host app doesn't install an OTel SDK
 * the API returns invalid no-op spans, [SpanContext.isValid] is false, and we skip
 * the trace bits — no crash, no fake data. Same idea for SLF4J: with no MDC backend
 * installed, the map is empty and we skip.
 */
public class ContextCapture(
    private val config: ContextPropagationConfig,
) {
    public fun snapshot(): ContextSnapshot? {
        val mdc = if (config.captureMdc) captureMdc() else emptyMap()
        val (traceparent, tracestate) = if (config.captureOtel) captureOtel() else (null to null)
        val snap = ContextSnapshot(mdc = mdc, traceparent = traceparent, tracestate = tracestate)
        return snap.takeUnless { it.isEmpty() }
    }

    private fun captureMdc(): Map<String, String> {
        val raw = org.slf4j.MDC.getCopyOfContextMap() ?: return emptyMap()
        val allow = config.mdcAllowList ?: return raw.toMap()      // null allow-list = capture all
        if (allow.isEmpty()) return emptyMap()
        return raw.filterKeys { it in allow }
    }

    private fun captureOtel(): Pair<String?, String?> {
        val ctx = Span.current().spanContext
        if (!ctx.isValid) return null to null
        // W3C trace-context: 00-<trace-id>-<span-id>-<flags>. OTel returns these
        // lowercase-hex already; flags is a 2-char hex string.
        val traceparent = "00-${ctx.traceId}-${ctx.spanId}-${ctx.traceFlags.asHex()}"
        val tracestate = ctx.traceState
            .takeIf { !it.isEmpty }
            ?.asMap()
            ?.entries
            ?.joinToString(",") { "${it.key}=${it.value}" }
        return traceparent to tracestate
    }
}
