@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package cs.trade.scheduler.engine.infra.domain.usecases

import cs.trade.scheduler.core.backend.usecases.BaseUseCase
import cs.trade.scheduler.core.backend.usecases.runCatchingWithLogging
import cs.trade.scheduler.storage.postgres.domain.repositories.JobRepository
import cs.trade.scheduler.storage.postgres.domain.repositories.JobTypePauseRepository
import cs.trade.scheduler.storage.postgres.domain.repositories.OutboxRepository
import cs.trade.scheduler.transport.rabbit.domain.JobTransport

/**
 * One batch tick of the outbox publisher loop: SELECT unpublished rows, publish to Rabbit,
 * mark as published. See DESIGN.md section 7.1.
 *
 * **Pause filter (DESIGN.md 22.1):** rows whose `payload_type` is in [JobTypePauseRepository]
 * are SKIPPED — `published_at` stays NULL, the row stays in outbox until the type is
 * unpaused (the next tick re-evaluates). One extra batch query per tick when at least
 * one type is paused; zero overhead when nothing is paused (set is empty).
 *
 * **At-least-once semantics:** the order is publish → markPublished. If the process dies
 * between those two, the next tick re-publishes the same outbox row, and the consumer
 * side is expected to be idempotent against the (jobId, attempts) pair (DESIGN.md 17.1).
 *
 * Note: pipelines/loops in :engine-infra do NOT follow the strict "1 function repo ↔ 1
 * UseCase" rule (DESIGN.md 3.3). This UseCase represents one *batch operation* spanning
 * outbox + transport.
 */
public class PublishOutboxBatchUseCase(
    private val outbox: OutboxRepository,
    private val transport: JobTransport,
    private val jobs: JobRepository,
    private val pauses: JobTypePauseRepository,
) : BaseUseCase() {

    public suspend operator fun invoke(batchSize: Int = DEFAULT_BATCH_SIZE): Result<Int> =
        runCatchingWithLogging {
            val batch = outbox.findUnpublished(batchSize)
            if (batch.isEmpty()) return@runCatchingWithLogging 0

            val pausedTypes = pauses.findPausedTypes()
            // Only pay the per-batch payload-type lookup when filtering is actually needed.
            // The map stays empty in the common no-pauses case, so the `in` check short-circuits.
            val payloadTypes = if (pausedTypes.isNotEmpty()) {
                jobs.findPayloadTypesByIds(batch.map { it.jobId })
            } else {
                emptyMap()
            }

            var published = 0
            for (entry in batch) {
                val type = payloadTypes[entry.jobId]
                if (type != null && type in pausedTypes) continue   // paused → skip, leave row
                transport.publish(
                    jobId = entry.jobId,
                    routingKey = entry.routingKey,
                    priority = entry.priority,
                    delayMillis = entry.delayMs,
                )
                outbox.markPublished(entry.id)
                published++
            }
            published
        }

    public companion object {
        public const val DEFAULT_BATCH_SIZE: Int = 100
    }
}
