package cs.trade.scheduler.engine.worker.infrastructure

import cs.trade.scheduler.core.backend.handler.JobHandler
import cs.trade.scheduler.engine.worker.domain.usecases.DeferPausedJobUseCase
import cs.trade.scheduler.engine.worker.domain.usecases.FinalizeJobUseCase
import cs.trade.scheduler.engine.worker.domain.usecases.PropagateRollupProgressUseCase
import cs.trade.scheduler.engine.worker.domain.usecases.ReportProgressUseCase
import cs.trade.scheduler.engine.worker.domain.usecases.ScheduleRetryUseCase
import cs.trade.scheduler.engine.worker.infrastructure.loops.HeartbeatLoop
import cs.trade.scheduler.engine.worker.infrastructure.loops.WorkerRegistryLoop
import cs.trade.scheduler.engine.worker.infrastructure.metrics.JobMetrics
import org.koin.core.module.Module
import org.koin.core.module.dsl.singleOf
import org.koin.dsl.module
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

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
 *     )
 * }
 * ```
 */
public class SchedulerWorkerConfig {
    public var nodeId: String = "worker-${System.currentTimeMillis()}"
    public var nodeTags: List<String> = emptyList()

    public var defaultConcurrency: Int = 10
    public var defaultPrefetch: Int? = null      // null = равно concurrency
    public var heartbeatInterval: Duration = 30.seconds
    public var lockDuration: Duration = 90.seconds
    public var shutdownTimeout: Duration = 30.seconds
    public var cancelGracePeriod: Duration = 30.seconds

    internal val queues: MutableList<QueueConfig> = mutableListOf()

    /** Declared queue names in registration order — exposed for [WorkerMetricsBinder] etc. */
    public val queueNames: List<String> get() = queues.map { it.name }

    public fun queue(
        name: String,
        concurrency: Int = defaultConcurrency,
        prefetch: Int? = null,
        defaultPriority: Int = 0,
        adaptive: AdaptivePrefetch? = null,
    ) {
        queues += QueueConfig(name, concurrency, prefetch ?: concurrency, defaultPriority, adaptive)
    }
}

public data class QueueConfig(
    val name: String,
    val concurrency: Int,
    val prefetch: Int,
    val defaultPriority: Int,
    val adaptive: AdaptivePrefetch? = null,
)

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
        singleOf(::WorkerInFlightCounter)
        singleOf(::PrefetchTuner)
        // Function-ref runtime — receives KFunction-encoded jobs from DefaultScheduler
        // (DESIGN.md 21). The runner needs the running Koin context to resolve target
        // beans by KClass + qualifier at execute time.
        single<FunctionRefRunner> {
            FunctionRefRunner(koin = getKoin(), json = get<cs.trade.scheduler.core.backend.SchedulerCoreConfig>().json)
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
            )
        }
    }
}
