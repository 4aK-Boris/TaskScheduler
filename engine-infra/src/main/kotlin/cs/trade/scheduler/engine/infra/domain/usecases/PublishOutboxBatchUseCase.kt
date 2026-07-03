@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package cs.trade.scheduler.engine.infra.domain.usecases

import cs.trade.scheduler.core.backend.usecases.BaseUseCase
import cs.trade.scheduler.core.backend.usecases.runCatchingWithLogging
import cs.trade.scheduler.storage.postgres.domain.repositories.OutboxRepository
import cs.trade.scheduler.transport.rabbit.domain.JobTransport

/**
 * One batch tick of the outbox publisher loop: SELECT unpublished rows, publish to Rabbit,
 * mark as published. See DESIGN.md section 7.1.
 *
 * **Pause filter (DESIGN.md 22.1)** lives in [OutboxRepository.findUnpublished]: rows whose
 * `payload_type` is paused are excluded at SQL level and park in the outbox until the type
 * is unpaused. It used to be an in-code skip here — that left paused rows at the head of
 * the id-ordered LIMIT window, and once a batch-size of them accumulated the batch was 100%
 * paused rows and every other type starved with zero errors logged (prod incident
 * 2026-07-01). A pause landing between the SELECT and the publish is still possible — the
 * worker's second-line pause check before execute covers that race.
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
) : BaseUseCase() {

    public suspend operator fun invoke(batchSize: Int = DEFAULT_BATCH_SIZE): Result<Int> =
        runCatchingWithLogging {
            val batch = outbox.findUnpublished(batchSize)
            if (batch.isEmpty()) return@runCatchingWithLogging 0

            var published = 0
            for (entry in batch) {
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
