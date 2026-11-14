package cs.trade.scheduler.engine.infra.infrastructure.loops

import cs.trade.scheduler.engine.infra.domain.usecases.FireDueRecurringJobsUseCase
import cs.trade.scheduler.engine.infra.infrastructure.SchedulerInfraConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory

/**
 * Durable recurring scheduler. Every [SchedulerInfraConfig.recurringPollInterval]
 * (default 5s), invoke [FireDueRecurringJobsUseCase] to fire all `recurring_job` rows
 * whose `next_trigger_at` has fallen past `now`. See DESIGN.md 7.5.
 * Completion notifications wake the same loop between polls for continuous definitions.
 *
 * Errors are logged and swallowed — the next tick retries. A persistent DB outage will
 * spam the log but not kill the loop.
 */
public class RecurringScheduler(
    private val fireDue: FireDueRecurringJobsUseCase,
    private val config: SchedulerInfraConfig,
    private val wakeupListener: RecurringWakeupListener? = null,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    public fun start(
        scope: CoroutineScope,
        isLeader: () -> Boolean = { true },
    ): Job = scope.launch {
        val intervalMs = config.recurringPollInterval.inWholeMilliseconds
        val wakeups = Channel<Unit>(Channel.CONFLATED)
        wakeupListener?.start(this) { wakeups.trySend(Unit) }
        log.info("RecurringScheduler started (interval={}ms)", intervalMs)
        while (isActive) {
            if (isLeader()) {
                fireDue().onFailure { log.error("Recurring fire batch failed", it) }
            }
            withTimeoutOrNull(intervalMs) { wakeups.receive() }
        }
    }
}
