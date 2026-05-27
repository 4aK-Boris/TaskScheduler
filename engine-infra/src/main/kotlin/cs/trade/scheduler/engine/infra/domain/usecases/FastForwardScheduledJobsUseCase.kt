@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package cs.trade.scheduler.engine.infra.domain.usecases

import cs.trade.scheduler.core.backend.SchedulerCoreConfig
import cs.trade.scheduler.core.backend.usecases.BaseUseCase
import cs.trade.scheduler.core.backend.usecases.runCatchingWithLogging
import cs.trade.scheduler.shared.JobState
import cs.trade.scheduler.storage.postgres.domain.models.Job
import cs.trade.scheduler.storage.postgres.domain.models.NewOutboxEntry
import cs.trade.scheduler.storage.postgres.domain.repositories.JobRepository
import cs.trade.scheduler.storage.postgres.domain.repositories.OutboxRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.transactions.suspendTransaction
import org.slf4j.LoggerFactory
import kotlin.time.Clock
import kotlin.time.Duration

/**
 * Promotion step: scan for `SCHEDULED` rows whose `scheduled_at` now sits inside the
 * fast-forward window (`now + SchedulerCoreConfig.fastForwardWindow`), then per row,
 * in one DB transaction:
 *
 *   1. `transitionState(SCHEDULED → ENQUEUED)` with CAS on version.
 *   2. `outbox.insert(delay_ms = max(0, scheduled_at − now))` — Rabbit's delayed
 *      exchange holds the message for the residual delay so the worker picks it up
 *      at the right moment.
 *
 * Symmetric with [cs.trade.scheduler.storage.postgres.infrastructure.scheduler.DefaultScheduler.scheduleAt]:
 * the direct branch there short-circuits straight into ENQUEUED + outbox when the
 * delay is already inside the window; this UseCase handles everything that was further
 * out.
 *
 * Returns the number of jobs actually promoted (CAS-conflicts skipped, e.g. if the user
 * meanwhile cancelled the row).
 *
 * Single replica per scheduler-infra container in MVP (DESIGN.md 14.3); multi-replica
 * via advisory-lock leader election is Phase 2.
 */
public class FastForwardScheduledJobsUseCase(
    private val database: Database,
    private val jobs: JobRepository,
    private val outbox: OutboxRepository,
    private val coreConfig: SchedulerCoreConfig,
) : BaseUseCase() {

    private val log = LoggerFactory.getLogger(javaClass)

    public suspend operator fun invoke(batchSize: Int = DEFAULT_BATCH_SIZE): Result<Int> =
        runCatchingWithLogging {
            val now = Clock.System.now()
            val windowEnd = now + coreConfig.fastForwardWindow
            val due = jobs.findScheduledDue(upperBound = windowEnd, limit = batchSize)
            if (due.isEmpty()) return@runCatchingWithLogging 0

            log.info(
                "FastForward picking up {} SCHEDULED row(s) within window={}",
                due.size, coreConfig.fastForwardWindow,
            )

            var promoted = 0
            for (row in due) {
                val ok = withContext(Dispatchers.IO) {
                    suspendTransaction(db = database) {
                        val transitioned = jobs.transitionState(
                            id = row.id,
                            expectedVersion = row.version,
                            newState = JobState.ENQUEUED,
                            lockedBy = null,
                            lockedUntilMillis = null,
                        )
                        if (!transitioned) return@suspendTransaction false

                        val delayMs = computeDelayMs(row.scheduledAt, now)
                        outbox.insert(
                            NewOutboxEntry(
                                jobId = row.id,
                                routingKey = routingKeyFor(row),
                                priority = row.priority.value,
                                delayMs = delayMs,
                            ),
                        )
                        true
                    }
                }
                if (ok) {
                    promoted++
                } else {
                    log.debug("FastForward skipped {} — version moved (cancelled?)", row.id)
                }
            }
            promoted
        }

    private fun computeDelayMs(scheduledAt: kotlin.time.Instant?, now: kotlin.time.Instant): Long {
        // SCHEDULED rows always have scheduledAt set by scheduleAt(); but be defensive.
        val target = scheduledAt ?: return 0
        val residual = target - now
        return if (residual.isPositive()) residual.inWholeMilliseconds else 0L
    }

    private fun routingKeyFor(job: Job): String = when {
        job.targetNode != null -> "node.${job.targetNode}"
        job.targetTag != null -> "tag.${job.targetTag}"
        else -> job.queue
    }

    public companion object {
        public const val DEFAULT_BATCH_SIZE: Int = 100
    }
}
