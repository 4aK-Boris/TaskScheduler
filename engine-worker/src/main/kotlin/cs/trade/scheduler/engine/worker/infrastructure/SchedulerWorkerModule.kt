package cs.trade.scheduler.engine.worker.infrastructure

import com.zaxxer.hikari.HikariDataSource
import cs.trade.scheduler.core.backend.handler.JobHandler
import cs.trade.scheduler.engine.worker.domain.usecases.DeferPausedJobUseCase
import cs.trade.scheduler.engine.worker.domain.usecases.FinalizeJobUseCase
import cs.trade.scheduler.engine.worker.domain.usecases.PropagateRollupProgressUseCase
import cs.trade.scheduler.engine.worker.domain.usecases.ReportProgressUseCase
import cs.trade.scheduler.engine.worker.domain.usecases.ScheduleRetryUseCase
import cs.trade.scheduler.engine.worker.infrastructure.loops.HeartbeatLoop
import cs.trade.scheduler.engine.worker.infrastructure.loops.WorkerRegistryLoop
import cs.trade.scheduler.engine.worker.infrastructure.metrics.JobMetrics
import cs.trade.scheduler.storage.postgres.infrastructure.events.JobCancelListener
import kotlinx.coroutines.CoroutineDispatcher
import org.koin.core.module.Module
import org.koin.core.module.dsl.singleOf
import org.koin.dsl.module
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

// Stable-across-restart default node id: the container/host name (HOSTNAME on Linux & most
// container runtimes, COMPUTERNAME on Windows, else the resolved local host name). Unlike the old
// timestamp default, a rebuild/restart reuses the SAME `worker` registry row (upsert → alive again)
// instead of leaving a dead row behind to pile up on the dashboard. Multi-worker-per-host
// deployments should set `nodeId` explicitly so each process still gets its own row.
private fun defaultNodeId(): String =
    sequenceOf(
        System.getenv("HOSTNAME"),
        System.getenv("COMPUTERNAME"),
        runCatching { java.net.InetAddress.getLocalHost().hostName }.getOrNull(),
    ).firstOrNull { !it.isNullOrBlank() } ?: "worker"

/**
 * Builder DSL for the worker pool config. See DESIGN.md section 13.1.
 *
 * ```
 * schedulerWorkerModule {
 *     nodeId = "app-1"
 *     defaultConcurrency = 10
 *     heartbeatInterval = 30.seconds
 *     lockDuration = 90.seconds
 *     queue("default", concurrency = 10)
 *     queue("email",   concurrency = 20)
 *     queue("heavy",   concurrency = 2, prefetch = 4)
 *     queue("variable",
 *         concurrency = 4,
 *         prefetch = 8,
 *         adaptive = AdaptivePrefetch(
 *             targetLatency = 2.seconds,
 *             minPrefetch = 4,
 *             maxPrefetch = 32,
 *         ),
 *         circuitBreaker = CircuitBreakerConfig(
 *             errorRateThreshold = 0.5,
 *             minSamples = 10,
 *             sampleWindow = 1.minutes,
 *             openDuration = 30.seconds,
 *         ),
 *     )
 * }
 * ```
 */
public class SchedulerWorkerConfig {
    // Defaults to the host name (stable across restarts). Override for multiple workers per host.
    public var nodeId: String = defaultNodeId()
    public var nodeTags: List<String> = emptyList()

    public var defaultConcurrency: Int = 10
    public var defaultPrefetch: Int? = null      // null = равно concurrency
    public var heartbeatInterval: Duration = 30.seconds
    public var lockDuration: Duration = 90.seconds
    public var shutdownTimeout: Duration = 30.seconds
    public var cancelGracePeriod: Duration = 30.seconds

    /**
     * Schema-drift alert (DESIGN.md 22.9). Invoked once at worker startup for each handled
     * payload type whose serialization schema changed since the previous deploy —
     * `(payloadType, previousHash, currentHash)`. `null` (default) = WARN log only; set it to
     * page / Slack / bump a metric. The check is best-effort and never blocks startup.
     */
    public var onSchemaDriftAlert: ((payloadType: String, previousHash: String, currentHash: String) -> Unit)? = null

    internal val queues: MutableList<QueueConfig> = mutableListOf()

    /** Declared queue names in registration order — exposed for [WorkerMetricsBinder] etc. */
    public val queueNames: List<String> get() = queues.map { it.name }

    public fun queue(
        name: String,
        concurrency: Int = defaultConcurrency,
        prefetch: Int? = null,
        defaultPriority: Int = 0,
        adaptive: AdaptivePrefetch? = null,
        circuitBreaker: CircuitBreakerConfig? = null,
        dispatcher: CoroutineDispatcher? = null,
    ) {
        queues += QueueConfig(name, concurrency, prefetch ?: concurrency, defaultPriority, adaptive, circuitBreaker, dispatcher)
    }
}

/**
 * Per-queue worker config. [dispatcher] (DESIGN.md 13.3) overrides the default
 * `Dispatchers.IO` used to run the handler body — useful for:
 *  - CPU-heavy jobs that want a dedicated `Dispatchers.Default`-backed pool so blocking
 *    on Rabbit redelivery doesn't starve other queues.
 *  - Single-thread executor per queue (`newSingleThreadExecutor().asCoroutineDispatcher()`)
 *    when handler code uses thread-local state that mustn't bleed across jobs.
 *  - A user-provided pool sized differently from Hikari / Rabbit prefetch budgets.
 *
 * Null = inherit the worker pool's default (which today is `Dispatchers.IO` propagated
 * from the consumer's coroutine scope). Doesn't affect the consumer loop itself — Rabbit
 * dispatch and PG pickup always run on `Dispatchers.IO`.
 */
public data class QueueConfig(
    val name: String,
    val concurrency: Int,
    val prefetch: Int,
    val defaultPriority: Int,
    val adaptive: AdaptivePrefetch? = null,
    val circuitBreaker: CircuitBreakerConfig? = null,
    val dispatcher: CoroutineDispatcher? = null,
)

/**
 * Per-queue circuit breaker (DESIGN.md 20.7 — Phase 3). When the rolling window of
 * handler outcomes shows an error rate above [errorRateThreshold] (with at least
 * [minSamples] samples to suppress noise), the breaker trips to OPEN: new pickups are
 * released back to Rabbit with a delay so other nodes can try (or the same node retries
 * after [openDuration]). After the cooldown, a single HALF_OPEN probe goes through —
 * success closes the breaker, failure re-opens it for another cycle.
 *
 * **State is per-node, in-memory.** No cross-node sync — if node A trips, node B keeps
 * serving. That's intentional: the breaker is a consumer-side protection for one
 * worker pool's downstream. Operator-driven pause across the cluster is `job_type_pause`
 * (DESIGN.md 22.1), not the breaker.
 *
 * **Released jobs go back through Rabbit, not lost.** A tripped breaker treats incoming
 * deliveries the same way `DeferPausedJobUseCase` treats paused job types: clear the
 * lock + re-publish the outbox row with `delayMs = openDuration`. So the job is alive
 * the whole time, just not attempted on this node.
 *
 * @param errorRateThreshold Trip when (failures / total) within the window exceeds this
 *   (range 0.0..1.0; e.g. 0.5 = 50%).
 * @param minSamples Don't trip until we've seen this many samples in the window —
 *   prevents a single early failure from killing the queue.
 * @param sampleWindow How far back to look for samples. Older outcomes age out.
 * @param openDuration How long the breaker stays OPEN before a HALF_OPEN probe is
 *   allowed. Used both for the state-transition timer AND for the delay on released
 *   re-publishes (so the message comes back exactly when we're ready to probe again).
 */
public data class CircuitBreakerConfig(
    val errorRateThreshold: Double,
    val minSamples: Int,
    val sampleWindow: Duration,
    val openDuration: Duration,
) {
    init {
        require(errorRateThreshold in 0.0..1.0) {
            "errorRateThreshold must be in [0.0, 1.0], got $errorRateThreshold"
        }
        require(minSamples >= 1) { "minSamples must be >= 1, got $minSamples" }
        require(sampleWindow.isPositive()) { "sampleWindow must be positive, got $sampleWindow" }
        require(openDuration.isPositive()) { "openDuration must be positive, got $openDuration" }
    }
}

/**
 * Per-queue adaptive prefetch tuner (DESIGN.md 20.7). When set on a queue, a background
 * loop samples the handler's execution duration and adjusts `channel.basicQos(prefetch)`
 * on the fly:
 *
 *  - **Overload signal** — p95 latency above [targetLatency] × 1.5 → halve prefetch
 *    (multiplicative decrease). Holding too many in-flight messages when each one is slow
 *    just bloats the local pool while other consumers starve.
 *  - **Idle signal** — p95 latency below [targetLatency] × 0.5 → additive bump up. Fast
 *    handlers keep round-trips down by holding more inventory.
 *  - **Dead band** between 0.5× and 1.5× target → no change. Avoids thrash around the
 *    target.
 *
 * Bounded by [minPrefetch] / [maxPrefetch]. Starts at the queue's configured prefetch.
 *
 * Samples roll on an [ArrayDeque] capped at [sampleWindowSize]; older samples are
 * dropped. Tuner runs at [tuneInterval] cadence — frequent enough to react inside a few
 * minutes, sparse enough that we don't whipsaw on a single slow job.
 *
 * @param targetLatency the "comfortable" handler runtime. Pick the median latency you
 *   want consumers tuned for under healthy load.
 * @param minPrefetch never go below this; should be ≥ `concurrency` so a busy queue
 *   doesn't starve its own worker pool.
 * @param maxPrefetch never go above this; cap on local-pool memory growth.
 * @param tuneInterval how often the tuner loop checks the window and may adjust prefetch.
 * @param sampleWindowSize sliding window size for p95 computation. Larger = smoother but
 *   slower to react; smaller = jumpier. 100 is a reasonable default for most workloads.
 */
public data class AdaptivePrefetch(
    val targetLatency: Duration,
    val minPrefetch: Int,
    val maxPrefetch: Int,
    val tuneInterval: Duration = 30.seconds,
    val sampleWindowSize: Int = 100,
) {
    init {
        require(minPrefetch >= 1) { "minPrefetch must be >= 1, got $minPrefetch" }
        require(maxPrefetch >= minPrefetch) { "maxPrefetch ($maxPrefetch) must be >= minPrefetch ($minPrefetch)" }
        require(targetLatency.isPositive()) { "targetLatency must be positive, got $targetLatency" }
        require(sampleWindowSize >= 10) { "sampleWindowSize must be >= 10 for meaningful p95, got $sampleWindowSize" }
    }
}

public fun schedulerWorkerModule(configure: SchedulerWorkerConfig.() -> Unit): Module {
    val config = SchedulerWorkerConfig().apply(configure)
    require(config.queues.isNotEmpty()) {
        "schedulerWorkerModule: at least one queue(...) call required"
    }

    return module {
        single<SchedulerWorkerConfig> { config }
        single<HandlerRegistry> { HandlerRegistry(getAll<JobHandler<*>>()) }
        // Schema-drift check (DESIGN.md 22.9) — run once in WorkerPool.start(). The alert hook
        // comes from this config; null → WARN log only. PayloadSchemaRepository is bound by
        // schedulerPostgresModule.
        single<SchemaDriftCheck> {
            SchemaDriftCheck(handlers = get(), payloadSchemas = get(), onDrift = config.onSchemaDriftAlert)
        }
        singleOf(::WorkerInFlightCounter)
        singleOf(::PrefetchTuner)
        // CircuitBreakerRegistry has a `clock` constructor param with a default; `singleOf`
        // tries to resolve it from Koin and fails because no binding exists. Use the
        // default arg explicitly via a manual `single { }` block.
        single<CircuitBreakerRegistry> { CircuitBreakerRegistry() }
        // Function-ref runtime — receives KFunction-encoded jobs from DefaultScheduler
        // (DESIGN.md 21). The runner needs the running Koin context to resolve target
        // beans by KClass + qualifier at execute time.
        single<FunctionRefRunner> {
            FunctionRefRunner(koin = getKoin(), json = get<cs.trade.scheduler.core.backend.SchedulerCoreConfig>().json)
        }
        // job_cancel listener (DESIGN.md 22.7) needs raw JDBC creds for a dedicated LISTEN
        // connection — same reason PostgresEventBus / LeaderElection bypass the Hikari pool.
        // Pull them off the bound DataSource, which the documented setup always makes a
        // HikariDataSource. A non-Hikari DataSource can override this binding manually.
        single<JobCancelListener> {
            val hikari = get<javax.sql.DataSource>() as? HikariDataSource
                ?: error(
                    "JobCancelListener needs a HikariDataSource to source raw JDBC credentials " +
                        "for its dedicated LISTEN connection. Bind a HikariDataSource (the documented " +
                        "setup) or override single<JobCancelListener> { ... } yourself.",
                )
            JobCancelListener(
                jdbcUrl = hikari.jdbcUrl,
                jdbcUser = hikari.username,
                jdbcPassword = hikari.password,
            )
        }
        singleOf(::ScheduleRetryUseCase)
        singleOf(::PropagateRollupProgressUseCase)
        singleOf(::FinalizeJobUseCase)
        singleOf(::ReportProgressUseCase)
        singleOf(::DeferPausedJobUseCase)
        singleOf(::HeartbeatLoop)
        singleOf(::WorkerRegistryLoop)
        // Default no-op sink — user-apps that want Prometheus histograms override this
        // by registering `single<JobMetrics> { MicrometerJobMetrics(get()) }` after
        // schedulerWorkerModule(...) (Koin's last-wins semantics).
        single<JobMetrics> { JobMetrics.Noop }
        single<WorkerPool> {
            WorkerPool(
                transport = get(),
                jobs = get(),
                handlers = get(),
                scheduleRetry = get(),
                finalize = get(),
                reportProgress = get(),
                deferPaused = get(),
                jobTypePauses = get(),
                heartbeatLoop = get(),
                workerRegistryLoop = get(),
                workerRegistry = get(),
                inFlight = get(),
                contextRestore = get(),
                metrics = get(),
                tracer = get(),
                workerConfig = get(),
                coreConfig = get(),
                prefetchTuner = get(),
                functionRefRunner = get(),
                circuitBreakers = get(),
                cancelListener = get(),
                schemaDriftCheck = get(),
            )
        }
    }
}
