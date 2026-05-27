package cs.trade.scheduler.engine.infra.infrastructure.loops

import cs.trade.scheduler.engine.infra.domain.usecases.FastForwardScheduledJobsUseCase
import cs.trade.scheduler.engine.infra.infrastructure.SchedulerInfraConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory

/**
 * Periodic promotion of SCHEDULED jobs into the fast-forward window. See DESIGN.md 11.5.
 *
 * Pairs with `DefaultScheduler.scheduleAt`: short-term schedules go straight into
 * ENQUEUED + outbox; long-term ones sit in SCHEDULED. This loop walks the SCHEDULED
 * list every [SchedulerInfraConfig.fastForwardPollInterval] (default 30s) and promotes
 * anything whose `scheduled_at` now sits within
 * `SchedulerCoreConfig.fastForwardWindow`.
 *
 * Errors are logged and swallowed — the next tick retries.
 */
public class FastForwardTask(
    private val promote: FastForwardScheduledJobsUseCase,
    private val config: SchedulerInfraConfig,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    public fun start(
        scope: CoroutineScope,
        isLeader: () -> Boolean = { true },
    ): Job = scope.launch {
        val intervalMs = config.fastForwardPollInterval.inWholeMilliseconds
        log.info("FastForwardTask started (interval={}ms)", intervalMs)
        while (isActive) {
            if (isLeader()) {
                promote().onFailure { log.error("FastForward batch failed", it) }
            }
            delay(intervalMs)
        }
    }
}
