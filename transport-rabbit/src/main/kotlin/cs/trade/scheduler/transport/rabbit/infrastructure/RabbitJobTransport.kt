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
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
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
        val consumerScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val handle = ConsumerHandleImpl(qName, consumerScope, prefetch)
        consumersMutex.withLock { activeConsumers.add(handle) }
        // First subscription. Re-subscription on an unexpected channel death is driven by the
        // ShutdownListener registered inside subscribe() — see its KDoc.
        subscribe(handle, handler)
        handle
    }

    /**
     * (Re)opens a channel for [handle] and starts consuming. The registered [com.rabbitmq.client.ShutdownListener]
     * re-invokes this method when the channel dies for a reason OTHER than our own cancel/close or a
     * connection-level drop:
     *
     * - **App-initiated** (our `cancel()`/`close()`, or the client closing deliberately) →
     *   `isInitiatedByApplication` is true → no recovery.
     * - **Connection-level drop** (`!connection.isOpen`) → the amqp-client's automatic recovery
     *   re-creates this channel and its consumer; re-subscribing here too would double-consume, so skip.
     * - **Channel-level protocol error** — the killer case: `406 PRECONDITION_FAILED` from
     *   `consumer_timeout` (an unacked delivery held past the broker's deadline). The amqp-client does
     *   NOT auto-recover channel-level closes, so without this the consumer would be gone until a full
     *   process restart (prod outage 2026-05-31). We re-subscribe after [RECONNECT_DELAY], retrying
     *   until it sticks or the handle is cancelled.
     */
    private fun subscribe(handle: ConsumerHandleImpl, handler: suspend (jobId: Uuid) -> Unit) {
        if (handle.closing) return
        val qName = handle.queue
        val channel = connection.createChannel()
        channel.basicQos(handle.prefetch)

        val deliverCallback = DeliverCallback { _, delivery ->
            val tag = delivery.envelope.deliveryTag
            val body = delivery.body
            if (body == null || body.size != UUID_BYTES) {
                log.warn("Unexpected body size={} on queue {} — routing to DLX", body?.size ?: -1, qName)
                runCatching { channel.basicNack(tag, /* multiple = */ false, /* requeue = */ false) }
                return@DeliverCallback
            }
            val jobId = Uuid.fromByteArray(body)
            handle.scope.launch {
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

        channel.addShutdownListener { cause ->
            when {
                handle.closing -> Unit // our cancel()/close() — leave it dead
                cause.isInitiatedByApplication -> Unit // deliberate close on our side
                !connection.isOpen ->
                    log.warn("Channel for queue {} closed by connection drop — amqp auto-recovery will re-consume", qName)
                else -> {
                    val reconnectDelay = config.reconnectDelay
                    log.error("Channel for queue {} closed by broker ({}) — re-subscribing in {}", qName, cause.reason, reconnectDelay)
                    handle.scope.launch {
                        while (isActive && !handle.closing) {
                            delay(reconnectDelay.inWholeMilliseconds)
                            if (!isActive || handle.closing) return@launch
                            val ok = runCatching { subscribe(handle, handler) }
                                .onFailure { log.error("Re-subscribe attempt failed for queue {} — retrying in {}", qName, reconnectDelay, it) }
                                .isSuccess
                            if (ok) return@launch // the fresh channel's own listener owns the next death
                        }
                    }
                }
            }
        }

        val consumerTag = channel.basicConsume(qName, /* autoAck = */ false, deliverCallback, cancelCallback)
        handle.bind(channel, consumerTag)
        log.info("Consumer started — queue={}, tag={}, prefetch={}", qName, consumerTag, handle.prefetch)
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

    /**
     * Mutable so [subscribe] can re-point it at a fresh channel/tag after a recovery without the
     * worker-side handle (held in WorkerPool.consumers) going stale. [prefetch] is tracked here so
     * a re-subscription re-applies the tuner's latest value, and [closing] gates recovery off once
     * the operator/shutdown has cancelled this consumer.
     */
    private class ConsumerHandleImpl(
        val queue: String,
        val scope: CoroutineScope,
        @Volatile var prefetch: Int,
    ) : ConsumerHandle {

        private val log = LoggerFactory.getLogger(javaClass)

        @Volatile private var channel: Channel? = null
        @Volatile private var consumerTag: String? = null

        @Volatile var closing: Boolean = false
            private set

        /** Point the handle at the live channel after a (re)subscription. */
        fun bind(channel: Channel, consumerTag: String) {
            this.channel = channel
            this.consumerTag = consumerTag
        }

        override suspend fun stopDeliveries() {
            // Idempotent: WorkerPool.stop() calls this first and cancel() after the drain —
            // the second pass must not basicCancel an already-cancelled tag.
            if (closing) return
            // Set before touching the channel so the ShutdownListener sees closing=true
            // and skips recovery.
            closing = true
            val ch = channel
            val tag = consumerTag
            withContext(Dispatchers.IO) {
                if (ch != null && tag != null) {
                    runCatching { ch.basicCancel(tag) }
                        .onFailure { log.warn("basicCancel failed for {}", tag, it) }
                }
            }
            // The channel deliberately stays open — in-flight handlers still ack/nack through
            // it — and the scope stays alive so they finish naturally. Teardown is cancel()'s job.
        }

        override suspend fun cancel() {
            stopDeliveries()
            withContext(Dispatchers.IO) {
                runCatching { channel?.close() }
                    .onFailure { log.warn("channel close failed for queue {}", queue, it) }
            }
            scope.cancel()
        }

        override suspend fun setPrefetch(prefetch: Int) {
            // Live update — basicQos can be called on a running channel at any time and
            // the broker honours it on the next dispatch. Doesn't affect already-in-flight
            // messages (the tuner's adjustment converges as those drain). Stored so a recovery
            // re-applies it.
            this.prefetch = prefetch
            withContext(Dispatchers.IO) {
                runCatching { channel?.basicQos(prefetch) }
                    .onFailure { log.warn("basicQos({}) failed on queue {}", prefetch, queue, it) }
            }
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
