package cs.trade.scheduler.engine.infra.infrastructure

import cs.trade.scheduler.core.backend.archival.ArchivalSink
import cs.trade.scheduler.engine.infra.domain.usecases.FastForwardScheduledJobsUseCase
import cs.trade.scheduler.engine.infra.domain.usecases.FireDueRecurringJobsUseCase
import cs.trade.scheduler.engine.infra.domain.usecases.PublishOutboxBatchUseCase
import cs.trade.scheduler.engine.infra.domain.usecases.RecoverOrphanedJobsUseCase
import cs.trade.scheduler.engine.infra.domain.usecases.RetentionCleanupBatchUseCase
import cs.trade.scheduler.engine.infra.infrastructure.loops.FastForwardTask
import cs.trade.scheduler.engine.infra.infrastructure.loops.OutboxPublisher
import cs.trade.scheduler.engine.infra.infrastructure.loops.RecurringScheduler
import cs.trade.scheduler.engine.infra.infrastructure.loops.RetentionCleanup
import cs.trade.scheduler.engine.infra.infrastructure.loops.SafetyNetPoller
import org.koin.core.module.Module
import org.koin.core.module.dsl.singleOf
import org.koin.dsl.module
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes

/**
 * Builder DSL for scheduler-infra container modules. See DESIGN.md sections 12.2, 18.3, 20.3.
 */
public class SchedulerInfraConfig {
    public var outboxPollInterval: Duration = 100.milliseconds

    /**
     * How often [RecurringScheduler] scans `recurring_job` for due rows. 5s (was 30s) so
     * 6-field sub-minute crons (`s m h dom mon dow`) fire on time. Keep it ≤ the smallest
     * cron period you actually use — otherwise the default `CATCH_UP_ONE` misfire policy
     * coalesces every slot missed between two polls into a single firing. Trade-off: smaller
     * value = a tighter (leader-gated) SELECT cadence on the DB.
     */
    public var recurringPollInterval: Duration = 5_000.milliseconds
    /**
     * How often [FastForwardTask] scans for SCHEDULED jobs about to fall inside
     * `SchedulerCoreConfig.fastForwardWindow`. The window itself lives in core config —
     * both `DefaultScheduler.scheduleAt` and the fast-forward loop use the same value.
     */
    public var fastForwardPollInterval: Duration = 30_000.milliseconds
    public var safetyNetPollInterval: Duration = 30_000.milliseconds

    public val retention: RetentionConfig = RetentionConfig()
    public var cleanupInterval: Duration = 1.hours
    public var cleanupBatchSize: Int = 10_000

    /**
     * Backlog warn threshold: every outbox publisher tick checks `countUnpublished`
     * after publish. If the backlog crosses this number (default 10k), a WARN log fires
     * once per breach. Pair with the existing `scheduler_outbox_unpublished` Prometheus
     * gauge for alerting. `null` disables the in-process warning entirely (relies on
     * external metrics alone).
     */
    public var outboxBacklogWarnThreshold: Int? = 10_000

    public val alerts: AlertsConfig = AlertsConfig()
}

public class RetentionConfig {
    public var succeeded: Duration? = 7.days
    public var failed: Duration? = 30.days
    public var cancelled: Duration? = 7.days
    public var outboxPublished: Duration? = 1.hours
    public var idempotencyLog: Duration? = 30.days
    public var deadWorkers: Duration? = 1.days
}

public class AlertsConfig {
    public var queueDepthThreshold: Map<String, Int> = emptyMap()
    public var onQueueDepthAlert: ((queue: String, depth: Int, threshold: Int) -> Unit)? = null
    public var alertCheckInterval: Duration = 1.minutes
}

public fun schedulerInfraModule(configure: SchedulerInfraConfig.() -> Unit = {}): Module {
    val config = SchedulerInfraConfig().apply(configure)
    return module {
        single<SchedulerInfraConfig> { config }
        // Default to the no-op sink (zero cost — single method-call indirection inside
        // the retention loop) so out-of-the-box behaviour stays "DELETE without archive",
        // matching the MVP contract. Compliance deployments override this binding AFTER
        // schedulerInfraModule in their startKoin block — Koin's last-wins semantics
        // mean a `single<ArchivalSink> { FileArchivalSink(Paths.get("/var/archive")) }`
        // declared in a later module wins without us needing feature flags. See
        // DESIGN.md 18.7 + the KDoc on ArchivalSink for the contract.
        single<ArchivalSink> { ArchivalSink.Noop }
        singleOf(::PublishOutboxBatchUseCase)
        singleOf(::OutboxPublisher)
        singleOf(::RecoverOrphanedJobsUseCase)
        singleOf(::SafetyNetPoller)
        singleOf(::FastForwardScheduledJobsUseCase)
        singleOf(::FastForwardTask)
        singleOf(::FireDueRecurringJobsUseCase)
        singleOf(::RecurringScheduler)
        singleOf(::RetentionCleanupBatchUseCase)
        singleOf(::RetentionCleanup)
    }
}
