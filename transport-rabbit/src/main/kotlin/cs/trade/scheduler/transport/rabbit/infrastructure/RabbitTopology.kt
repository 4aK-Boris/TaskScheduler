package cs.trade.scheduler.transport.rabbit.infrastructure

import com.rabbitmq.client.Channel

/**
 * Idempotent declare of exchanges + queues + bindings. Called once at startup on both
 * scheduler-infra (publisher) and user-app (consumers). See DESIGN.md section 11.3.
 *
 * - `jobs.dispatch` (x-delayed-message → direct, durable)
 * - `jobs.dlx` (direct, durable), with `q.dead-letter` bound under every queue's own routing
 *   key — a dead-lettered message keeps the key it came in with, so binding the DLQ only
 *   under the empty key silently discarded all of them
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
        // 🔴 This binding alone does NOT catch dead-lettered messages, and for a long time
        // it was the only one. Absent an explicit `x-dead-letter-routing-key`, the broker
        // re-publishes to the DLX under the key the message originally arrived with —
        // `default`, `marketplace`, … — and a direct exchange matches keys exactly. Every
        // rejected or expired message therefore went out unroutable and was dropped without
        // a trace, which is why `q.dead-letter` sat permanently empty while discards stayed
        // invisible. The per-queue binds below are what actually make the DLQ work; the empty
        // key stays for messages published straight to the DLX.
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
            // Out on the same key as in — see the note above. Binds are idempotent, so this
            // also repairs a broker declared by an older version on the next startup.
            channel.queueBind(DLQ, DLX_EXCHANGE, queue)
        }
    }

    public companion object {
        public const val DISPATCH_EXCHANGE: String = "jobs.dispatch"
        public const val DLX_EXCHANGE: String = "jobs.dlx"
        public const val DLQ: String = "q.dead-letter"
    }
}
