@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package cs.trade.scheduler.transport.rabbit.infrastructure

import com.rabbitmq.client.AMQP
import com.rabbitmq.client.CancelCallback
import com.rabbitmq.client.Channel
import com.rabbitmq.client.Connection
import com.rabbitmq.client.ConnectionFactory
import com.rabbitmq.client.DeliverCallback
import cs.trade.scheduler.transport.rabbit.domain.ConsumerHandle
import cs.trade.scheduler.transport.rabbit.domain.JobTransport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import kotlin.uuid.Uuid

/**
 * RabbitMQ-backed [JobTransport] for MVP. See DESIGN.md sections 11.3–11.6.
 *
 * Topology is declared once at construction via [RabbitTopology.declare] over the publisher
 * channel — so the very first publish never races a missing exchange/queue.
 *
 * Wire layout:
 * - One [Connection] shared by publisher + consumers (RabbitMQ best practice — channels
 *   are cheap, connections are not).
 * - One dedicated publisher [Channel] guarded by [publishMutex]. `Channel` is **not**
 *   thread-safe per the Java client docs, and `basicPublish` is fast enough that a single
 *   serialised channel scales fine for MVP throughput targets.
 * - One [Channel] per active consumer (each runs its own dispatch thread inside the
 *   amqp-client).
 *
 * Body protocol: exactly 16 bytes — the [Uuid] big-endian bytes from
 * [Uuid.toByteArray]. The worker re-loads the full job from PG (DESIGN.md 11.4 —
 * Postgres is the source of truth, Rabbit is just a delivery hint).
 *
 * Delay: `delayMillis > 0` adds the `x-delay` header consumed by the
 * `rabbitmq_delayed_message_exchange` plugin. The plugin caps at ~Integer.MAX_VALUE ms
 * (~24 days); our fast-forward window is 24h so this is safely below.
 */
public class RabbitJobTransport(
    factory: ConnectionFactory,
    topology: RabbitTopology,
    private val config: SchedulerRabbitConfig,
) : JobTransport {

    private val log = LoggerFactory.getLogger(javaClass)

    private val connection: Connection = factory.newConnection("scheduler-jobs")
    private val publishChannel: Channel = connection.createChannel()
    private val publishMutex = Mutex()

    private val consumersMutex = Mutex()
    private val activeConsumers = mutableListOf<ConsumerHandleImpl>()

    init {
        topology.declare(publishChannel)
        log.info("RabbitJobTransport initialised — connection={}, queues={}", connection, config.queues)
    }

    override suspend fun publish(
        jobId: Uuid,
        routingKey: String,
        priority: Int,
        delayMillis: Long,
    ) {
        val body = jobId.toByteArray()
        val propsBuilder = AMQP.BasicProperties.Builder()
            .deliveryMode(PERSISTENT)
            .priority(priority.coerceIn(0, MAX_PRIORITY))
        if (delayMillis > 0) {
            // Plugin reads `x-delay` as a 32-bit integer; we already bounded delay
            // via :engine-infra FastForwardTask (≤ fastForwardWindow = 24h).
            propsBuilder.headers(mapOf<String, Any>("x-delay" to delayMillis.toInt()))
        }
        val props = propsBuilder.build()

        withContext(Dispatchers.IO) {
            publishMutex.withLock {
                publishChannel.basicPublish(
                    RabbitTopology.DISPATCH_EXCHANGE,
                    routingKey,
                    props,
                    body,
                )
            }
        }
    }

    override suspend fun consume(
        queue: String,
        prefetch: Int,
        handler: suspend (jobId: Uuid) -> Unit,
    ): ConsumerHandle = withContext(Dispatchers.IO) {
        val qName = "q.$queue"
        val channel = connection.createChannel()
        channel.basicQos(prefetch)

        val consumerScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        val deliverCallback = DeliverCallback { _, delivery ->
            val tag = delivery.envelope.deliveryTag
            val body = delivery.body
            if (body == null || body.size != UUID_BYTES) {
                log.warn("Unexpected body size={} on queue {} — routing to DLX", body?.size ?: -1, qName)
                channel.basicNack(tag, /* multiple = */ false, /* requeue = */ false)
                return@DeliverCallback
            }
            val jobId = Uuid.fromByteArray(body)
            consumerScope.launch {
                try {
                    handler(jobId)
                    channel.basicAck(tag, false)
                } catch (t: Throwable) {
                    log.error("Consumer handler failed for job {} on queue {}", jobId, qName, t)
                    // requeue=false → DLX, matches "no automatic redelivery — retry is owned
                    // by the JobRepository state machine" (DESIGN.md 11.4).
                    runCatching { channel.basicNack(tag, false, false) }
                }
            }
        }

        val cancelCallback = CancelCallback { consumerTag ->
            log.info("Consumer {} on queue {} was cancelled by the broker", consumerTag, qName)
        }

        val consumerTag = channel.basicConsume(qName, /* autoAck = */ false, deliverCallback, cancelCallback)
        val handle = ConsumerHandleImpl(channel, consumerTag, consumerScope, qName)
        consumersMutex.withLock { activeConsumers.add(handle) }
        log.info("Consumer started — queue={}, tag={}, prefetch={}", qName, consumerTag, prefetch)
        handle
    }

    override suspend fun cancelAllConsumers() {
        val snapshot = consumersMutex.withLock {
            val copy = activeConsumers.toList()
            activeConsumers.clear()
            copy
        }
        snapshot.forEach { it.cancel() }
    }

    override suspend fun close() {
        cancelAllConsumers()
        withContext(Dispatchers.IO) {
            runCatching { publishChannel.close() }
                .onFailure { log.warn("publish channel close failed", it) }
            runCatching { connection.close() }
                .onFailure { log.warn("connection close failed", it) }
        }
    }

    private class ConsumerHandleImpl(
        private val channel: Channel,
        private val consumerTag: String,
        private val scope: CoroutineScope,
        private val queue: String,
    ) : ConsumerHandle {

        private val log = LoggerFactory.getLogger(javaClass)

        override suspend fun cancel() {
            withContext(Dispatchers.IO) {
                runCatching { channel.basicCancel(consumerTag) }
                    .onFailure { log.warn("basicCancel failed for {}", consumerTag, it) }
                runCatching { channel.close() }
                    .onFailure { log.warn("channel close failed for queue {}", queue, it) }
            }
            scope.cancel()
        }
    }

    private companion object {
        const val PERSISTENT = 2
        const val MAX_PRIORITY = 10
        const val UUID_BYTES = 16
    }
}

/** Helper exposed so module wiring can call `Closeable.close()` from a JVM shutdown hook. */
public fun JobTransport.closeBlocking(): Unit = runBlocking { close() }
