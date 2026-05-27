package cs.trade.scheduler.core.backend.context

import io.opentelemetry.api.trace.Span
import io.opentelemetry.api.trace.SpanContext
import io.opentelemetry.api.trace.TraceFlags
import io.opentelemetry.api.trace.TraceState
import io.opentelemetry.context.Context
import io.opentelemetry.context.Scope
import kotlinx.coroutines.ThreadContextElement
import kotlinx.coroutines.slf4j.MDCContext
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext

/**
 * Rebuilds a coroutine context from the JSON stashed in `job.context_json` so the
 * handler sees the same MDC and OTel parent span as the enqueuing call. See DESIGN.md
 * 22.11.
 *
 * `extraMdc` is overlaid AFTER the captured map — used by WorkerPool to inject
 * `job_id`/`job_queue`/`job_attempt` so even jobs enqueued without context still get
 * useful log keys for free.
 *
 * Parse failures don't crash the worker: the handler still runs, just without the
 * propagated context. A misformatted traceparent in storage is an ops problem, not a
 * reason to fail the job.
 */
public class ContextRestore(
    private val json: Json,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * Returned to the worker so it can both (a) wrap `handler.execute` in a coroutine
     * context that propagates MDC + OTel-current-span, and (b) start its own child span
     * off the propagated parent. Splitting the two pieces avoids having WorkerPool
     * re-parse `context_json`.
     */
    public data class Restored(
        val coroutineContext: CoroutineContext,
        /** Parent OTel [Context] from the captured traceparent, or null if absent / malformed. */
        val otelParent: Context?,
    )

    public fun restore(contextJson: String?, extraMdc: Map<String, String>): Restored {
        val snapshot = parse(contextJson)
        val mdc = if (snapshot != null) snapshot.mdc + extraMdc else extraMdc

        var ctx: CoroutineContext = if (mdc.isNotEmpty()) MDCContext(mdc) else EmptyCoroutineContext

        val otelCtx = snapshot?.traceparent?.let { buildOtelContext(it, snapshot.tracestate) }
        if (otelCtx != null) ctx += OtelContextElement(otelCtx)

        return Restored(coroutineContext = ctx, otelParent = otelCtx)
    }

    private fun parse(contextJson: String?): ContextSnapshot? {
        if (contextJson.isNullOrBlank()) return null
        return runCatching { json.decodeFromString(ContextSnapshot.serializer(), contextJson) }
            .onFailure { log.warn("Could not parse job.context_json — running without propagated context", it) }
            .getOrNull()
    }

    private fun buildOtelContext(traceparent: String, tracestate: String?): Context? {
        // 00-<32 hex trace-id>-<16 hex span-id>-<2 hex flags>
        val parts = traceparent.split('-')
        if (parts.size != 4 || parts[0] != "00" || parts[1].length != 32 ||
            parts[2].length != 16 || parts[3].length != 2
        ) {
            log.warn("Malformed traceparent: {}", traceparent)
            return null
        }
        val state = tracestate
            ?.split(',')
            ?.fold(TraceState.builder()) { builder, entry ->
                val kv = entry.split('=', limit = 2)
                if (kv.size == 2) builder.put(kv[0].trim(), kv[1].trim()) else builder
            }
            ?.build()
            ?: TraceState.getDefault()
        val spanContext = SpanContext.createFromRemoteParent(
            /* traceIdHex = */ parts[1],
            /* spanIdHex  = */ parts[2],
            /* flags      = */ TraceFlags.fromHex(parts[3], 0),
            /* traceState = */ state,
        )
        return Context.root().with(Span.wrap(spanContext))
    }
}

/**
 * Coroutine glue for OpenTelemetry: makes the given [Context] current on whatever
 * thread the coroutine is dispatched to, and restores the prior context on suspend.
 * Done manually so we don't have to depend on `opentelemetry-extension-kotlin` for one
 * tiny class.
 *
 * Combining two [OtelContextElement] values via `+` replaces the earlier one (single
 * [Key]). WorkerPool uses that: ContextRestore puts the parent in, the auto-span layer
 * swaps it for `parent.with(childSpan)` so handlers see the child as current.
 */
public class OtelContextElement(
    public val otelContext: Context,
) : ThreadContextElement<Scope> {

    public companion object Key : CoroutineContext.Key<OtelContextElement>

    override val key: CoroutineContext.Key<*> get() = Key

    override fun updateThreadContext(context: CoroutineContext): Scope = otelContext.makeCurrent()

    override fun restoreThreadContext(context: CoroutineContext, oldState: Scope) {
        oldState.close()
    }
}
