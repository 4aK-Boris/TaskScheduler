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
    ) {
        queues += QueueConfig(name, concurrency, prefetch ?: concurrency, defaultPriority)
    }
}

public data class QueueConfig(
    val name: String,
    val concurrency: Int,
    val prefetch: Int,
    val defaultPriority: Int,
)

public fun schedulerWorkerModule(configure: SchedulerWorkerConfig.() -> Unit): Module {
    val config = SchedulerWorkerConfig().apply(configure)
    require(config.queues.isNotEmpty()) {
        "schedulerWorkerModule: at least one queue(...) call required"
    }

    return module {
        single<SchedulerWorkerConfig> { config }
        single<HandlerRegistry> { HandlerRegistry(getAll<JobHandler<*>>()) }
        singleOf(::WorkerInFlightCounter)
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
            )
        }
    }
}
