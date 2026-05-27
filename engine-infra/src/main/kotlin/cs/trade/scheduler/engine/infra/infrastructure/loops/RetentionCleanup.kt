package cs.trade.scheduler.engine.infra.infrastructure.loops

import cs.trade.scheduler.engine.infra.domain.usecases.RetentionCleanupBatchUseCase
import cs.trade.scheduler.engine.infra.infrastructure.SchedulerInfraConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory

/**
 * Periodic retention cleanup. Every [SchedulerInfraConfig.cleanupInterval] (default 1h),
 * invoke [RetentionCleanupBatchUseCase] which deletes terminal job rows + published
 * outbox rows past their `retention.*` thresholds. See DESIGN.md section 18.
 *
 * Errors are logged and swallowed — the next tick retries. A backlog larger than
 * `cleanupBatchSize` simply takes multiple ticks to drain.
 */
public class RetentionCleanup(
    private val cleanupBatch: RetentionCleanupBatchUseCase,
    private val config: SchedulerInfraConfig,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    public fun start(
        scope: CoroutineScope,
        isLeader: () -> Boolean = { true },
    ): Job = scope.launch {
        val intervalMs = config.cleanupInterval.inWholeMilliseconds
        log.info("RetentionCleanup started (interval={}ms)", intervalMs)
        while (isActive) {
            if (isLeader()) {
                cleanupBatch().onFailure { log.error("Retention cleanup batch failed", it) }
            }
            delay(intervalMs)
        }
    }
}
