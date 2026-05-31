@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package cs.trade.scheduler.transport.rabbit.infrastructure

import com.rabbitmq.client.AMQP
import com.rabbitmq.client.CancelCallback
import com.rabbitmq.client.Channel
import com.rabbitmq.client.Connection
import com.rabbitmq.client.ConnectionFactory
import com.rabbitmq.client.DeliverCallback
import com.rabbitmq.client.Delivery
import com.rabbitmq.client.Envelope
import com.rabbitmq.client.ShutdownListener
import com.rabbitmq.client.ShutdownSignalException
import io.mockk.CapturingSlot
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.Runs
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.time.Duration.Companion.milliseconds
import kotlin.uuid.Uuid

/**
 * Unit-проверки [RabbitJobTransport] на mockk-замоканных `Connection`/`Channel`.
 * Фокус — на том, чего раньше не было и что положило прод 2026-05-31: авто-re-subscribe
 * консьюмера после серверного закрытия канала (`406 PRECONDITION_FAILED` от `consumer_timeout`),
 * и корректное НЕ-восстановление для app-initiated / connection-level закрытий.
 */
class RabbitJobTransportTest {

    private lateinit var factory: ConnectionFactory
    private lateinit var connection: Connection
    private lateinit var topology: RabbitTopology
    private lateinit var publishChannel: Channel
    private lateinit var consumerChannel1: Channel
    private lateinit var consumerChannel2: Channel
    private lateinit var config: SchedulerRabbitConfig
    private lateinit var transport: RabbitJobTransport

    private lateinit var deliverSlot: CapturingSlot<DeliverCallback>
    private lateinit var cancelSlot: CapturingSlot<CancelCallback>
    private lateinit var shutdownSlot: CapturingSlot<ShutdownListener>

    @BeforeEach
    fun setUp() {
        deliverSlot = slot()
        cancelSlot = slot()
        shutdownSlot = slot()

        factory = mockk()
        connection = mockk(relaxed = true)
        topology = mockk()
        publishChannel = mockk(relaxed = true)
        consumerChannel1 = mockConsumerChannel(tag = "ctag-1")
        consumerChannel2 = mockConsumerChannel(tag = "ctag-2")

        config = SchedulerRabbitConfig().apply {
            connectionFactory = factory
            queues = listOf("default")
            // Маленькая задержка, чтобы async re-subscribe в тестах не ждал реальные 5с.
            reconnectDelay = 30.milliseconds
        }

        every { factory.newConnection("scheduler-jobs") } returns connection
        every { connection.createChannel() } returnsMany listOf(publishChannel, consumerChannel1, consumerChannel2)
        every { connection.isOpen } returns true
        every { topology.declare(any()) } just Runs

        transport = RabbitJobTransport(factory = factory, topology = topology, config = config)
    }

    @Test
    fun `successful delivery invokes handler and acks`() {
        val handled = CopyOnWriteArrayList<Uuid>()
        val jobId = Uuid.random()

        runBlocking { transport.consume(queue = "default", prefetch = 10) { id -> handled.add(id) } }
        deliverSlot.captured.handle("ctag-1", delivery(tag = 1L, body = jobId.toByteArray()))

        verify(timeout = 2_000) { consumerChannel1.basicAck(1L, false) }

        assertEquals(listOf(jobId), handled.toList())
    }

    @Test
    fun `handler failure nacks to DLX without requeue`() {
        val handled = CopyOnWriteArrayList<Uuid>()
        val jobId = Uuid.random()

        runBlocking {
            transport.consume(queue = "default", prefetch = 10) { id -> handled.add(id); error("boom") }
        }
        deliverSlot.captured.handle("ctag-1", delivery(tag = 7L, body = jobId.toByteArray()))

        verify(timeout = 2_000) { consumerChannel1.basicNack(7L, false, false) }
        verify(exactly = 0, timeout = 500) { consumerChannel1.basicAck(any(), any()) }

        assertEquals(listOf(jobId), handled.toList())
    }

    @Test
    fun `malformed body is nacked without invoking handler`() {
        val handled = CopyOnWriteArrayList<Uuid>()

        runBlocking { transport.consume(queue = "default", prefetch = 10) { id -> handled.add(id) } }
        deliverSlot.captured.handle("ctag-1", delivery(tag = 3L, body = ByteArray(8)))

        verify { consumerChannel1.basicNack(3L, false, false) }

        Thread.sleep(150)
        assertTrue(handled.isEmpty(), "handler must not run for a malformed body")
    }

    @Test
    fun `broker-initiated channel close with open connection triggers re-subscribe`() {
        every { connection.isOpen } returns true

        runBlocking { transport.consume(queue = "default", prefetch = 10) { } }
        shutdownSlot.captured.shutdownCompleted(brokerClose())

        // Re-subscribe должен открыть НОВЫЙ канал и заново подписаться.
        verify(timeout = 2_000) { connection.createChannel() }
        verify(timeout = 2_000) {
            consumerChannel2.basicConsume(any<String>(), any<Boolean>(), any<DeliverCallback>(), any<CancelCallback>())
        }
    }

    @Test
    fun `application-initiated channel close does not re-subscribe`() {
        runBlocking { transport.consume(queue = "default", prefetch = 10) { } }
        shutdownSlot.captured.shutdownCompleted(appClose())

        Thread.sleep(200)
        verify(exactly = 0) {
            consumerChannel2.basicConsume(any<String>(), any<Boolean>(), any<DeliverCallback>(), any<CancelCallback>())
        }
    }

    @Test
    fun `channel close during connection outage is left to amqp auto-recovery`() {
        every { connection.isOpen } returns false

        runBlocking { transport.consume(queue = "default", prefetch = 10) { } }
        shutdownSlot.captured.shutdownCompleted(brokerClose())

        Thread.sleep(200)
        verify(exactly = 0) {
            consumerChannel2.basicConsume(any<String>(), any<Boolean>(), any<DeliverCallback>(), any<CancelCallback>())
        }
    }

    @Test
    fun `cancel cancels consumer and suppresses recovery`() {
        val handle = runBlocking { transport.consume(queue = "default", prefetch = 10) { } }

        runBlocking { handle.cancel() }
        shutdownSlot.captured.shutdownCompleted(brokerClose())

        verify { consumerChannel1.basicCancel("ctag-1") }
        verify { consumerChannel1.close() }

        Thread.sleep(200)
        verify(exactly = 0) {
            consumerChannel2.basicConsume(any<String>(), any<Boolean>(), any<DeliverCallback>(), any<CancelCallback>())
        }
    }

    @Test
    fun `setPrefetch applies basicQos on the live channel`() {
        val handle = runBlocking { transport.consume(queue = "default", prefetch = 10) { } }

        runBlocking { handle.setPrefetch(25) }

        verify { consumerChannel1.basicQos(25) }
    }

    private fun mockConsumerChannel(tag: String): Channel {
        val channel = mockk<Channel>(relaxed = true)
        every {
            channel.basicConsume(any<String>(), any<Boolean>(), capture(deliverSlot), capture(cancelSlot))
        } returns tag
        every { channel.addShutdownListener(capture(shutdownSlot)) } just Runs
        return channel
    }

    private fun delivery(tag: Long, body: ByteArray): Delivery {
        val envelope = Envelope(tag, /* redeliver = */ false, "jobs.dispatch", "default")
        return Delivery(envelope, AMQP.BasicProperties.Builder().build(), body)
    }

    /** Серверное закрытие канала (не нами): isInitiatedByApplication=false — путь восстановления. */
    private fun brokerClose(): ShutdownSignalException =
        ShutdownSignalException(/* hardError = */ false, /* initiatedByApplication = */ false, /* reason = */ null, /* ref = */ null)

    /** Закрытие, инициированное приложением (наш cancel/close) — восстановление НЕ нужно. */
    private fun appClose(): ShutdownSignalException =
        ShutdownSignalException(/* hardError = */ false, /* initiatedByApplication = */ true, /* reason = */ null, /* ref = */ null)
}
