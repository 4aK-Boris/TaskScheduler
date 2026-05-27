@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package cs.trade.scheduler.engine.worker.domain.usecases

import cs.trade.scheduler.core.backend.usecases.BaseUseCase
import cs.trade.scheduler.core.backend.usecases.runCatchingWithLogging
import cs.trade.scheduler.storage.postgres.domain.models.NewOutboxEntry
import cs.trade.scheduler.storage.postgres.domain.repositories.JobRepository
import cs.trade.scheduler.storage.postgres.domain.repositories.OutboxRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.transactions.suspendTransaction
import kotlin.time.Duration
import kotlin.uuid.Uuid

/**
 * Schedules one retry of a failed job. Two writes in ONE DB transaction:
 *
 *   1. `job.markForRetry` — PROCESSING → AWAITING_RETRY, clears the lock, bumps version,
 *      records `scheduled_at = now + backoff` (informational; the actual re-delivery is
 *      driven by Rabbit's delayed exchange via the outbox row below).
 *   2. `outbox.insert(delay_ms = backoff)` — published by [OutboxPublisher] to the
 *      `jobs.dispatch` exchange with header `x-delay = backoff`. The plugin holds the
 *      message, then routes it to the same queue the original was on so a worker picks
 *      it up afresh.
 *
 * Returns `false` on optimistic-lock conflict (someone else mutated the row between
 * pickup and now). On conflict, the outbox INSERT is rolled back — no duplicate retry
 * scheduled.
 */
public class ScheduleRetryUseCase(
    private val database: Database,
    private val jobs: JobRepository,
    private val outbox: OutboxRepository,
) : BaseUseCase() {

    public suspend operator fun invoke(
        jobId: Uuid,
        expectedVersion: Int,
        backoff: Duration,
        routingKey: String,
        priority: Int,
        errorMsg: String? = null,
        errorStack: String? = null,
    ): Result<Boolean> = runCatchingWithLogging {
        withContext(Dispatchers.IO) {
            suspendTransaction(db = database) {
                val updated = jobs.markForRetry(jobId, expectedVersion, backoff, errorMsg, errorStack)
                if (!updated) {
                    return@suspendTransaction false
                }
                outbox.insert(
                    NewOutboxEntry(
                        jobId = jobId,
                        routingKey = routingKey,
                        priority = priority,
                        delayMs = backoff.inWholeMilliseconds,
                    ),
                )
                true
            }
        }
    }
}
