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
 * **Misfire handling:**
 *  - `CATCH_UP_ONE` (default): fire once now, set `nextTriggerAt = next future cron slot`.
 *  - `SKIP`: same effective behaviour — fire once when picked up. (DESIGN.md's strict
 *    "skip if missed" requires downtime tracking we don't have yet.)
 *  - `CATCH_UP_ALL`: fire one job per missed cron slot in `[nextTriggerAt, now]` (for
 *    snapshot/billing workloads where every run matters). Bounded by
 *    [MAX_CATCH_UP_PER_TICK] per tick — if more slots are still missed after the cap, the
 *    next trigger is left at the next un-fired slot so the remainder catches up on later
 *    ticks rather than flooding one transaction or being dropped.
 *
 * The per-row return value of the firing path is the number of `job` rows created, so a
 * single `CATCH_UP_ALL` row can contribute many. [invoke] sums them.
 *
 * **Bad cron:** if parsing or `nextExecution` throws (invalid expression slipped in, or a
 * degenerate cron with no future), disable the row so the loop doesn't keep failing on the
 * same one — surfaced to operators via `enabled=false`.
 *
 * Single replica per scheduler-infra container in MVP. Multi-replica via advisory-lock
 * leader election is Phase 2 (DESIGN.md 14.3); the CAS on `last_triggered_at` already
 * prevents double-fire even before then — for `CATCH_UP_ALL` the whole batch of N inserts
 * shares the one CAS, so a losing replica rolls back all N.
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
                val plan = runCatching { planFiring(row, now) }.getOrElse { t ->
                    log.error(
                        "Invalid/expired cron '{}' on recurring '{}' — disabling row to stop the bleed",
                        row.cron, row.id, t,
                    )
                    recurringJobs.disable(row.id)
                    continue
                }
                if (plan.capped) {
                    log.warn(
                        "CATCH_UP_ALL for '{}' capped at {} occurrence(s) this tick; remaining missed " +
                            "slots will fire on subsequent ticks",
                        row.id, MAX_CATCH_UP_PER_TICK,
                    )
                }
                fired += fire(row, now, plan)
            }
            fired
        }

    /** How many jobs to create for [row] this tick and where its next trigger lands. */
    private fun planFiring(row: RecurringJobRow, now: Instant): FiringPlan = when (row.misfirePolicy) {
        MisfirePolicy.CATCH_UP_ALL -> {
            val cu = CronExpr.catchUpPlan(
                expression = row.cron,
                firstMissed = row.nextTriggerAt,
                now = now,
                timezone = row.timezone,
                limit = MAX_CATCH_UP_PER_TICK,
            )
            FiringPlan(occurrences = cu.occurrences, nextTrigger = cu.nextTrigger, capped = cu.capped)
        }
        // CATCH_UP_ONE + SKIP collapse the backlog into a single fire and resume from the
        // next future slot. Strict SKIP-if-missed needs downtime tracking we don't have.
        MisfirePolicy.CATCH_UP_ONE, MisfirePolicy.SKIP ->
            FiringPlan(occurrences = 1, nextTrigger = CronExpr.nextAfter(row.cron, now, row.timezone), capped = false)
    }

    private data class FiringPlan(val occurrences: Int, val nextTrigger: Instant, val capped: Boolean)

    /** Returns the number of `job` rows created (0 if another replica won the CAS). */
    private suspend fun fire(row: RecurringJobRow, now: Instant, plan: FiringPlan): Int =
        withContext(Dispatchers.IO) {
            suspendTransaction(db = database) {
                // CAS the recurring row first — if another replica fired in between our
                // findDue and now, this returns false and the rest of the txn rolls back
                // (including every catch-up job insert below).
                val claimed = recurringJobs.markFiredAndScheduleNext(
                    id = row.id,
                    expectedLastTriggeredAt = row.lastTriggeredAt,
                    firedAt = now,
                    next = plan.nextTrigger,
                )
                if (!claimed) {
                    log.debug("Recurring '{}' already fired by another replica — skipping", row.id)
                    return@suspendTransaction 0
                }
                repeat(plan.occurrences) { insertJobAndOutbox(row, now) }
                plan.occurrences
            }
        }

    private suspend fun insertJobAndOutbox(row: RecurringJobRow, now: Instant) {
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
            timeoutSeconds = row.timeoutSeconds,
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
    }

    private fun routingKeyFor(row: RecurringJobRow): String = when {
        row.targetNode != null -> "node.${row.targetNode}"
        row.targetTag != null -> "tag.${row.targetTag}"
        else -> row.queue
    }

    public companion object {
        public const val DEFAULT_BATCH_SIZE: Int = 100

        /**
         * Upper bound on `CATCH_UP_ALL` jobs created for one recurring row in a single tick.
         * Keeps a long downtime with a frequent cron from flooding one transaction; the
         * remainder is not dropped — it fires on subsequent ticks (see [planFiring]).
         */
        public const val MAX_CATCH_UP_PER_TICK: Int = 500
    }
}
