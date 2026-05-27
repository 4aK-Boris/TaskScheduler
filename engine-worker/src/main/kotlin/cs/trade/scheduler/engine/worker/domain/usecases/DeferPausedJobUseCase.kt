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
 * Worker-side handling of the pause second-line check (DESIGN.md 22.1): a job got
 * picked up before the pause set was refreshed (or pause happened mid-flight). We:
 *
 *  1. Release the PROCESSING lock back to ENQUEUED (no attempt increment — this isn't
 *     a failure, just a deferral). CAS on version protects against racing.
 *  2. Insert a new outbox row with `delay_ms = backoff` so Rabbit's delayed exchange
 *     republishes after the configured wait — gives the pause time to clear.
 *
 * Both writes go in the same DB tx — partial application would leave the job in either
 * an unowned state with no re-delivery scheduled, or a phantom outbox row pointing at
 * a still-locked row. The atomicity is the whole point of the outbox pattern.
 *
 * Returns `false` if the CAS lost (someone else mutated the row first — leave it alone).
 */
public class DeferPausedJobUseCase(
    private val database: Database,
    private val jobs: JobRepository,
    private val outbox: OutboxRepository,
) : BaseUseCase() {

    public suspend operator fun invoke(
        jobId: Uuid,
        expectedVersion: Int,
        routingKey: String,
        priority: Int,
        backoff: Duration,
    ): Result<Boolean> = runCatchingWithLogging {
        withContext(Dispatchers.IO) {
            suspendTransaction(db = database) {
                val released = jobs.releaseProcessingLock(jobId, expectedVersion)
                if (!released) return@suspendTransaction false
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
