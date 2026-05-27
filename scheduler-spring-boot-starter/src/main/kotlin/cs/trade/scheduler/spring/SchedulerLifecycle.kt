package cs.trade.scheduler.spring

import cs.trade.scheduler.engine.worker.infrastructure.WorkerPool
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory
import org.springframework.context.SmartLifecycle

/**
 * Spring-managed start/stop hook for [WorkerPool]. Equivalent to the explicit
 * `workerPool.start()` / `workerPool.stop()` calls in `standalone-runner`'s
 * `Application.kt`, but with Spring driving ordering — `isAutoStartup = true` means
 * the bean fires inside `ApplicationContext.start()` (the normal Spring Boot startup
 * flow) and `stop()` runs during context close.
 *
 * The Koin-built [WorkerPool] uses `suspend fun start()/stop()`; we bridge through
 * `runBlocking`. That's acceptable here because Spring's lifecycle thread is happy to
 * block — there's no coroutine context to integrate with at this layer.
 *
 * Phase ordering: [SmartLifecycle.getPhase] defaults to 0; we keep it there so the
 * worker pool starts after DataSource/Rabbit beans (which aren't Lifecycle-aware,
 * so they're constructed in the regular dependency phase, before any Lifecycle starts).
 */
public class SchedulerLifecycle(
    private val workerPool: WorkerPool,
    private val autoStart: Boolean,
) : SmartLifecycle {
    private val log = LoggerFactory.getLogger(javaClass)

    @Volatile private var running: Boolean = false

    override fun isAutoStartup(): Boolean = autoStart

    override fun start() {
        if (running) return
        log.info("SchedulerLifecycle.start — booting WorkerPool")
        runBlocking { workerPool.start() }
        running = true
    }

    override fun stop() {
        if (!running) return
        log.info("SchedulerLifecycle.stop — draining WorkerPool")
        runBlocking { workerPool.stop() }
        running = false
    }

    /**
     * Spring calls this overload when shutting down the context. The default impl in
     * [SmartLifecycle] calls [stop] then [Runnable.run]; we keep that contract so the
     * lifecycle processor doesn't time out waiting for the callback.
     */
    override fun stop(callback: Runnable) {
        try {
            stop()
        } finally {
            callback.run()
        }
    }

    override fun isRunning(): Boolean = running
}
