package cs.trade.scheduler.core.backend

import cs.trade.scheduler.core.backend.archival.ArchivalSink
import cs.trade.scheduler.core.backend.context.ContextCapture
import cs.trade.scheduler.core.backend.context.ContextRestore
import cs.trade.scheduler.core.backend.events.EventBus
import cs.trade.scheduler.core.backend.events.InMemoryEventBus
import cs.trade.scheduler.core.backend.handler.retry.RetryPolicy
import cs.trade.scheduler.core.backend.idempotency.IdempotencyMetrics
import io.opentelemetry.api.OpenTelemetry
import io.opentelemetry.api.trace.Tracer
import kotlinx.serialization.json.Json
import org.koin.core.module.Module
import org.koin.dsl.module
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes

/**
 * Top-level builder for the scheduler library. Provides defaults that other modules read.
 * See DESIGN.md sections 8.1 and 12.2.
 *
 * ```
 * startKoin {
 *     modules(
 *         schedulerCoreModule {
 *             nodeId = "app-1"
 *             defaultJobTimeout = 5.minutes
 *             defaultMaxAttempts = 3
 *         },
 *         schedulerPostgresModule { ... },
 *         schedulerRabbitModule { ... },
 *         schedulerWorkerModule { ... },     // optional — only on user-app worker nodes
 *     )
 * }
 * ```
 */
public class SchedulerCoreConfig {
    public var nodeId: String = "node-${System.currentTimeMillis()}"
    public var defaultQueue: String = "default"
    public var defaultJobTimeout: Duration = 5.minutes
    public var defaultMaxAttempts: Int = 3
    public var defaultRetryPolicy: RetryPolicy? = null
    public val contextPropagation: ContextPropagationConfig = ContextPropagationConfig()

    /**
     * DAG guardrails (DESIGN.md 22.10). Fail-fast `IllegalArgumentException` at enqueue —
     * before any DB write — when one `enqueueAfter(...)` waits on more than [maxDagFanIn]
     * distinct parents, or `chain(...)` is given more than [maxChainLength] steps. These cap
     * accidental / runaway shapes (a barrier on 100k parents, a 100k-step chain); real DAGs are
     * nowhere near, so the defaults never bite. Raise either to effectively disable that check.
     *
     * NOTE: transitive DAG *depth* and per-parent *fan-out* are intentionally NOT bounded —
     * they'd cost a graph walk / an extra COUNT query per enqueue, disproportionate for a guard.
     */
    public var maxDagFanIn: Int = 1_000
    public var maxChainLength: Int = 1_000

    /**
     * Cutoff for routing `scheduleAt` calls. Jobs scheduled `now + fastForwardWindow` or
     * sooner go straight into `ENQUEUED + outbox(delay)` so Rabbit's delayed exchange
     * handles the wait. Jobs further out sit in `SCHEDULED` and get promoted by
     * `FastForwardTask` when they fall inside the window. Default 24h follows DESIGN.md 11.5.
     */
    public var fastForwardWindow: Duration = 24.hours

    /**
     * JSON used for payload (de)serialisation. Defaults follow DESIGN.md 22.9 — schema-evolution
     * friendly. Override only if you really need stricter parsing.
     */
    public var json: Json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = false
        classDiscriminator = "_type"
    }
}

public class ContextPropagationConfig {
    public var captureMdc: Boolean = true
    public var captureOtel: Boolean = true
    /** Null = capture all MDC keys; recommended explicit allowlist in prod. See DESIGN.md 22.11. */
    public var mdcAllowList: List<String>? = null
}

public fun schedulerCoreModule(configure: SchedulerCoreConfig.() -> Unit = {}): Module {
    val config = SchedulerCoreConfig().apply(configure)
    return module {
        single<SchedulerCoreConfig> { config }
        // Single in-process bus for dashboard WS events. Multi-replica deployments
        // will swap this for a Postgres LISTEN/NOTIFY or Rabbit-fanout backed impl.
        single<EventBus> { InMemoryEventBus() }
        // Context propagation (DESIGN.md 22.11). Capture runs on the enqueue path
        // inside DefaultScheduler; restore runs in WorkerPool around handler.execute.
        single<ContextCapture> { ContextCapture(config.contextPropagation) }
        single<ContextRestore> { ContextRestore(config.json) }
        // OTel default = noop. Apps with an SDK override `single<OpenTelemetry>` with
        // GlobalOpenTelemetry.get() / AutoConfiguredOpenTelemetrySdk.initialize() and the
        // Tracer derives from it. Noop produces non-recording spans — zero overhead, no
        // dropped data, no requirement for users without observability infra.
        single<OpenTelemetry> { OpenTelemetry.noop() }
        single<Tracer> { get<OpenTelemetry>().getTracer(TRACER_NAME) }
        // Archival default = no-op. Deployments needing forensic retention override
        // with `single<ArchivalSink> { FileArchivalSink(...) }` or an S3-backed impl
        // (Koin last-wins). Retention loop calls the sink before DELETE; failures
        // make the loop skip the DELETE and try again next tick.
        single<ArchivalSink> { ArchivalSink.Noop }
        // Idempotency metrics default = no-op. Apps with a MeterRegistry override with
        // `single<IdempotencyMetrics> { MicrometerIdempotencyMetrics(get()) }` to feed
        // `scheduler_idempotency_dedup_total{action}` per DESIGN.md 22.5.
        single<IdempotencyMetrics> { IdempotencyMetrics.Noop }
        // Scheduler impl is registered by an infrastructure-layer module
        // (e.g. schedulerPostgresModule) — :core:backend stays free of storage deps.
    }
}

/** Instrumentation scope name reported in OTel traces (`otel.library.name` resource attr). */
public const val TRACER_NAME: String = "cs.trade.scheduler"
