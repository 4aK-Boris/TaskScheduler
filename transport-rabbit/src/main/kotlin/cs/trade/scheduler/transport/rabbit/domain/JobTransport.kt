package cs.trade.scheduler.transport.rabbit.domain

import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * Push-dispatch transport contract. RabbitMQ-backed in MVP, pluggable for Kafka in Phase 3+.
 * See DESIGN.md section 11 for topology details.
 *
 * Message body is *only* the job UUID; the worker loads payload from PG at pickup
 * (single source of truth — DESIGN.md 11.4).
 */
@OptIn(ExperimentalUuidApi::class)
public interface JobTransport {

    /**
     * Publish a job ID to the dispatch exchange.
     *
     * @param routingKey usually equals the queue name; for node-pinning becomes `node.{nodeId}`
     *                   or `tag.{tag}` (DESIGN.md 22.2).
     * @param priority   0..10, mapped to AMQP BasicProperties.priority.
     * @param delayMillis 0 = immediate; >0 = x-delay header for the delayed exchange.
     */
    public suspend fun publish(
        jobId: Uuid,
        routingKey: String,
        priority: Int,
        delayMillis: Long,
    )

    /**
     * Start consuming a queue, calling [handler] on each delivery. Returns a handle that
     * cancels the consumer (used by graceful shutdown — DESIGN.md 13.6).
     */
    public suspend fun consume(
        queue: String,
        prefetch: Int,
        handler: suspend (jobId: Uuid) -> Unit,
    ): ConsumerHandle

    /** Cancel all consumers and close channels. The underlying Connection stays open. */
    public suspend fun cancelAllConsumers()

    /** Close everything — used by hard shutdown. */
    public suspend fun close()
}

public interface ConsumerHandle {
    public suspend fun cancel()
}
