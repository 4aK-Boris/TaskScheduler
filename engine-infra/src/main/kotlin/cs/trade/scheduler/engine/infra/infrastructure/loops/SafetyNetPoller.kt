package cs.trade.scheduler.engine.infra.infrastructure.loops

import cs.trade.scheduler.engine.infra.domain.usecases.RecoverOrphanedJobsUseCase
import cs.trade.scheduler.engine.infra.infrastructure.SchedulerInfraConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory

/**
 * Periodic orphan recovery: every [SchedulerInfraConfig.safetyNetPollInterval] (default
 * 30s), invoke [RecoverOrphanedJobsUseCase] which finds PROCESSING rows whose
 * `locked_until` is in the past and re-queues them. See DESIGN.md 13.5.
 *
 * Lives in `:engine-infra`, runs as a single replica per scheduler-infra container in
 * MVP. Multi-replica via advisory-lock leader election is Phase 2.
 *
 * Errors are logged and swallowed — the next tick retries. A persistent DB outage will
 * spam the log but not kill the loop.
 */
public class SafetyNetPoller(
    private val recoverOrphans: RecoverOrphanedJobsUseCase,
    private val config: SchedulerInfraConfig,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    public fun start(
        scope: CoroutineScope,
        isLeader: () -> Boolean = { true },
    ): Job = scope.launch {
        val intervalMs = config.safetyNetPollInterval.inWholeMilliseconds
        log.info("SafetyNetPoller started (interval={}ms)", intervalMs)
        while (isActive) {
            if (isLeader()) {
                recoverOrphans().onFailure { log.error("SafetyNet batch failed", it) }
            }
            delay(intervalMs)
        }
    }
}
