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

    /**
     * Phase 1 of graceful shutdown: stop the broker from delivering NEW messages, while the
     * channel and the handler scope stay alive — deliveries already in flight finish and
     * ack/nack normally. Follow up with [cancel] for full teardown once they have drained.
     *
     * Default delegates to [cancel], so single-phase transports keep their old semantics;
     * implementations MUST keep `cancel()` safe to call after `stopDeliveries()`.
     */
    public suspend fun stopDeliveries() {
        cancel()
    }

    /**
     * Full teardown: stop deliveries (if [stopDeliveries] wasn't called), close the channel
     * and cancel the handler scope — coroutines still running get CancellationException.
     */
    public suspend fun cancel()

    /**
     * Update the consumer's prefetch (Rabbit `basicQos`) without restarting it. Called by
     * the worker-side adaptive tuner (DESIGN.md 20.7) to grow/shrink the in-flight window
     * based on observed handler latency. Default impl is a no-op — non-Rabbit transports
     * that don't model prefetch can ignore it; the tuner just won't move them.
     */
    public suspend fun setPrefetch(prefetch: Int) {
        // intentionally empty — see KDoc
    }
}
