package cs.trade.scheduler.transport.rabbit.infrastructure

import com.rabbitmq.client.Channel
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Test

/**
 * Топология объявляется на моках — брокер здесь не нужен, проверяется ровно набор bind'ов.
 *
 * Смысл теста: dead-lettered сообщение уходит в DLX под тем routing key, с которым пришло в
 * очередь (`default`, `marketplace`, …), а `jobs.dlx` — direct, то есть совпадение точное.
 * Пока DLQ был привязан только под пустым ключом, каждое отбракованное сообщение оказывалось
 * unroutable и молча пропадало: `q.dead-letter` стоял вечно пустым, а отбраковка — невидимой.
 */
class RabbitTopologyTest {

    @Test
    fun `DLQ привязан под ключом каждой очереди — иначе dead-letter уходит в никуда`() {
        val channel = mockk<Channel>(relaxed = true)

        RabbitTopology(configuredQueues = listOf("default", "marketplace", "heavy")).declare(channel)

        verify { channel.queueBind(RabbitTopology.DLQ, RabbitTopology.DLX_EXCHANGE, "default") }
        verify { channel.queueBind(RabbitTopology.DLQ, RabbitTopology.DLX_EXCHANGE, "marketplace") }
        verify { channel.queueBind(RabbitTopology.DLQ, RabbitTopology.DLX_EXCHANGE, "heavy") }
    }

    @Test
    fun `пустой ключ остаётся — под ним публикуют в DLX напрямую`() {
        val channel = mockk<Channel>(relaxed = true)

        RabbitTopology(configuredQueues = listOf("default")).declare(channel)

        verify { channel.queueBind(RabbitTopology.DLQ, RabbitTopology.DLX_EXCHANGE, "") }
    }

    @Test
    fun `каждая очередь по-прежнему привязана к dispatch под своим именем`() {
        val channel = mockk<Channel>(relaxed = true)

        RabbitTopology(configuredQueues = listOf("default", "steam")).declare(channel)

        verify { channel.queueBind("q.default", RabbitTopology.DISPATCH_EXCHANGE, "default") }
        verify { channel.queueBind("q.steam", RabbitTopology.DISPATCH_EXCHANGE, "steam") }
    }
}
