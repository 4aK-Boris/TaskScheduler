package cs.trade.scheduler.transport.rabbit.infrastructure

import com.rabbitmq.client.Channel

/**
 * Idempotent declare of exchanges + queues + bindings. Called once at startup on both
 * scheduler-infra (publisher) and user-app (consumers). See DESIGN.md section 11.3.
 *
 * - `jobs.dispatch` (x-delayed-message → direct, durable)
 * - `jobs.dlx` (direct, durable)
 * - one queue per logical queue name (`q.{name}`, x-max-priority=10, x-dead-letter-exchange=jobs.dlx)
 * - `q.dead-letter` for unparseable messages or unknown payload_type
 *
 * Per-node and per-tag queues (`q.node.{id}`, `q.tag.{tag}`) are declared on demand by the
 * `:engine-worker` module when the user configures `nodeTags` (DESIGN.md 22.2).
 */
public class RabbitTopology(
    private val configuredQueues: List<String>,
) {

    public fun declare(channel: Channel) {
        // Dispatch exchange (delayed-message plugin)
        channel.exchangeDeclare(
            DISPATCH_EXCHANGE,
            "x-delayed-message",
            /* durable = */ true,
            /* autoDelete = */ false,
            mapOf("x-delayed-type" to "direct"),
        )

        // DLX for unroutable / unparseable
        channel.exchangeDeclare(DLX_EXCHANGE, "direct", true, false, null)
        channel.queueDeclare(DLQ, true, false, false, null)
        channel.queueBind(DLQ, DLX_EXCHANGE, "")

        // Per-queue declare + bind to dispatch with routing key = queue name
        val queueArgs = mapOf(
            "x-max-priority" to 10,
            "x-dead-letter-exchange" to DLX_EXCHANGE,
        )
        for (queue in configuredQueues) {
            val qName = "q.$queue"
            channel.queueDeclare(qName, true, false, false, queueArgs)
            channel.queueBind(qName, DISPATCH_EXCHANGE, queue)
        }
    }

    public companion object {
        public const val DISPATCH_EXCHANGE: String = "jobs.dispatch"
        public const val DLX_EXCHANGE: String = "jobs.dlx"
        public const val DLQ: String = "q.dead-letter"
    }
}
