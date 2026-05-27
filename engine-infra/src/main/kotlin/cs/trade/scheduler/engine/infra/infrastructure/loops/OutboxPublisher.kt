package cs.trade.scheduler.engine.infra.infrastructure.loops

import cs.trade.scheduler.core.backend.SchedulerCoreConfig
import cs.trade.scheduler.engine.infra.domain.usecases.PublishOutboxBatchUseCase
import cs.trade.scheduler.engine.infra.infrastructure.SchedulerInfraConfig
import cs.trade.scheduler.storage.postgres.domain.repositories.OutboxRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory

/**
 * Background coroutine: PG outbox → Rabbit dispatch. See DESIGN.md 7.1 / 14.3.
 *
 * **Multi-replica:** pass a leader-gate via [start]'s `isLeader` lambda — the tick body
 * is skipped on follower replicas. Default `{ true }` keeps single-replica deployments
 * unchanged. Even without gating, the loop is correctness-safe (outbox row
 * `markPublished` is CAS-guarded) — gating just removes the wasted publish-then-CAS-loss
 * Rabbit roundtrip on each follower.
 *
 * **Backpressure warning:** every [BACKLOG_CHECK_TICKS] ticks (default ~3s of 100ms
 * ticks), we sample `outbox.countUnpublished` and log WARN if it exceeds
 * [SchedulerInfraConfig.outboxBacklogWarnThreshold]. Cheap COUNT(*) on the partial
 * `outbox_unpublished_idx`; doesn't slow the hot publish path. Pair with the existing
 * `scheduler_outbox_unpublished` Prometheus gauge for proper alerting.
 */
public class OutboxPublisher(
    private val publishBatch: PublishOutboxBatchUseCase,
    private val outbox: OutboxRepository,
    private val config: SchedulerInfraConfig,
    // Injected for log correlation only — when multi-replica is enabled (Phase 2) the
    // leader's nodeId appearing in backlog WARN tells you which replica is the publisher
    // at the moment of the breach. Followers don't log backlog WARNs (gated by isLeader).
    private val coreConfig: SchedulerCoreConfig,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    public fun start(
        scope: CoroutineScope,
        intervalMillis: Long = 100L,
        isLeader: () -> Boolean = { true },
    ): Job = scope.launch {
        log.info("OutboxPublisher started (interval={}ms)", intervalMillis)
        var sinceCheck = 0
        // Latch so we don't spam a WARN every check — fires once per breach, resets when
        // the backlog falls back below the threshold (catching up after a Rabbit outage).
        var inBreach = false
        while (isActive) {
            if (isLeader()) {
                publishBatch().onFailure { log.error("Outbox batch failed", it) }
            }
            if (++sinceCheck >= BACKLOG_CHECK_TICKS && isLeader()) {
                sinceCheck = 0
                val threshold = config.outboxBacklogWarnThreshold
                if (threshold != null) {
                    runCatching { outbox.countUnpublished() }.onSuccess { backlog ->
                        if (backlog > threshold) {
                            if (!inBreach) {
                                log.warn(
                                    "Outbox backlog {} > threshold {} on node={} — publisher falling behind " +
                                        "(check Rabbit health, scheduler_outbox_unpublished gauge)",
                                    backlog, threshold, coreConfig.nodeId,
                                )
                                inBreach = true
                            }
                        } else if (inBreach) {
                            log.info("Outbox backlog recovered on node={} ({} <= {})", coreConfig.nodeId, backlog, threshold)
                            inBreach = false
                        }
                    }
                }
            }
            delay(intervalMillis)
        }
    }

    public companion object {
        /** Sample COUNT(*) every N publish ticks. At 100ms ticks → check every ~3s. */
        public const val BACKLOG_CHECK_TICKS: Int = 30
    }
}
