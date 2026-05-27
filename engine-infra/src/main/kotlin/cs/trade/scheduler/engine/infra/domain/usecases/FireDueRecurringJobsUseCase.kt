@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package cs.trade.scheduler.engine.infra.domain.usecases

import cs.trade.scheduler.core.backend.SchedulerCoreConfig
import cs.trade.scheduler.core.backend.cron.CronExpr
import cs.trade.scheduler.core.backend.usecases.BaseUseCase
import cs.trade.scheduler.core.backend.usecases.runCatchingWithLogging
import cs.trade.scheduler.shared.JobPriority
import cs.trade.scheduler.shared.JobState
import cs.trade.scheduler.shared.MisfirePolicy
import cs.trade.scheduler.storage.postgres.domain.models.NewOutboxEntry
import cs.trade.scheduler.storage.postgres.domain.models.RecurringJobRow
import cs.trade.scheduler.storage.postgres.domain.models.Job as JobRow
import cs.trade.scheduler.storage.postgres.domain.repositories.JobRepository
import cs.trade.scheduler.storage.postgres.domain.repositories.OutboxRepository
import cs.trade.scheduler.storage.postgres.domain.repositories.RecurringJobRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.transactions.suspendTransaction
import org.slf4j.LoggerFactory
import kotlin.time.Clock
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * One tick of the recurring scheduler. Pulls due `recurring_job` rows and fires them:
 *   1. compute next trigger via cron (in the row's timezone),
 *   2. in one DB transaction:
 *      - CAS `markFiredAndScheduleNext` (skips if another infra replica beat us),
 *      - INSERT a fresh `job` row (state=ENQUEUED) carrying the recurring's payload,
 *      - INSERT an outbox row (delay=0) — published by [OutboxPublisher] to a worker.
 *
 * **Misfire handling (MVP):**
 *  - `CATCH_UP_ONE` (default): fire once now, set `nextTriggerAt = next future cron slot`.
 *  - `SKIP`: same effective behaviour — fire once when picked up. (DESIGN.md's strict
 *    "skip if missed" requires downtime tracking we don't have yet.)
 *  - `CATCH_UP_ALL`: treated as `CATCH_UP_ONE` for MVP + log a WARN.
 *
 * **Bad cron:** if `nextExecution` throws (invalid expression slipped in), disable the
 * row so the loop doesn't keep failing on the same one and surface to operators via
 * `enabled=false`.
 *
 * Single replica per scheduler-infra container in MVP. Multi-replica via advisory-lock
 * leader election is Phase 2 (DESIGN.md 14.3); the CAS on `last_triggered_at` already
 * prevents double-fire even before then.
 */
public class FireDueRecurringJobsUseCase(
    private val database: Database,
    private val recurringJobs: RecurringJobRepository,
    private val jobs: JobRepository,
    private val outbox: OutboxRepository,
    private val coreConfig: SchedulerCoreConfig,
) : BaseUseCase() {

    private val log = LoggerFactory.getLogger(javaClass)

    public suspend operator fun invoke(batchSize: Int = DEFAULT_BATCH_SIZE): Result<Int> =
        runCatchingWithLogging {
            val now = Clock.System.now()
            val due = recurringJobs.findDue(now, batchSize)
            if (due.isEmpty()) return@runCatchingWithLogging 0

            log.info("RecurringScheduler firing {} due row(s)", due.size)
            var fired = 0
            for (row in due) {
                if (row.misfirePolicy == MisfirePolicy.CATCH_UP_ALL) {
                    log.warn(
                        "CATCH_UP_ALL is not implemented in MVP for '{}' — firing once (CATCH_UP_ONE behavior)",
                        row.id,
                    )
                }

                val nextTrigger = runCatching {
                    CronExpr.nextAfter(row.cron, now, row.timezone)
                }.getOrElse { t ->
                    log.error(
                        "Invalid cron '{}' on recurring '{}' — disabling row to stop the bleed",
                        row.cron, row.id, t,
                    )
                    recurringJobs.disable(row.id)
                    continue
                }

                if (fireOne(row, now, nextTrigger)) fired++
            }
            fired
        }

    private suspend fun fireOne(row: RecurringJobRow, now: Instant, next: Instant): Boolean =
        withContext(Dispatchers.IO) {
            suspendTransaction(db = database) {
                // CAS the recurring row first — if another replica fired in between our
                // findDue and now, this returns false and the rest of the txn rolls back.
                val claimed = recurringJobs.markFiredAndScheduleNext(
                    id = row.id,
                    expectedLastTriggeredAt = row.lastTriggeredAt,
                    firedAt = now,
                    next = next,
                )
                if (!claimed) {
                    log.debug("Recurring '{}' already fired by another replica — skipping", row.id)
                    return@suspendTransaction false
                }

                val jobId = Uuid.random()
                val jobRow = JobRow(
                    id = jobId,
                    state = JobState.ENQUEUED,
                    queue = row.queue,
                    priority = JobPriority(row.priority),
                    payloadType = row.payloadType,
                    payloadJson = row.payloadJson,
                    scheduledAt = null,
                    attempts = 0,
                    maxAttempts = coreConfig.defaultMaxAttempts,
                    timeoutSeconds = null,
                    lockedBy = null,
                    lockedUntil = null,
                    pendingDeps = 0,
                    version = 0,
                    idempotencyKey = null,
                    targetNode = row.targetNode,
                    targetTag = row.targetTag,
                    progress = null,
                    progressMsg = null,
                    progressUpdatedAt = null,
                    startedAt = null,
                    durationMs = null,
                    cancelRequestedAt = null,
                    cancelRequestedBy = null,
                    contextJson = null,
                    createdAt = now,
                    updatedAt = now,
                )
                jobs.insert(jobRow)
                outbox.insert(
                    NewOutboxEntry(
                        jobId = jobId,
                        routingKey = routingKeyFor(row),
                        priority = row.priority,
                        delayMs = 0,
                    ),
                )
                true
            }
        }

    private fun routingKeyFor(row: RecurringJobRow): String = when {
        row.targetNode != null -> "node.${row.targetNode}"
        row.targetTag != null -> "tag.${row.targetTag}"
        else -> row.queue
    }

    public companion object {
        public const val DEFAULT_BATCH_SIZE: Int = 100
    }
}
