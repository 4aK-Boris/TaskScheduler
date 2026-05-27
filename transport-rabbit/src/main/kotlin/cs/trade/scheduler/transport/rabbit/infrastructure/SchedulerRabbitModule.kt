package cs.trade.scheduler.transport.rabbit.infrastructure

import com.rabbitmq.client.ConnectionFactory
import cs.trade.scheduler.transport.rabbit.domain.JobTransport
import org.koin.core.module.Module
import org.koin.dsl.module

/**
 * Builder DSL config for the RabbitMQ transport module. See DESIGN.md section 12.2.
 *
 * Usage:
 * ```
 * startKoin {
 *     modules(
 *         schedulerRabbitModule {
 *             connectionFactory = ConnectionFactory().apply { ... }
 *             queues = listOf("default", "emails")
 *             prefetchPerConsumer = 20
 *         },
 *     )
 * }
 * ```
 *
 * `RabbitJobTransport` declares the topology eagerly at construction — the first
 * `JobTransport` resolve (e.g. by `PublishOutboxBatchUseCase`) is enough to provision
 * exchanges + queues.
 */
public class SchedulerRabbitConfig {
    public var connectionFactory: ConnectionFactory? = null
    public var queues: List<String> = listOf("default")
    public var prefetchPerConsumer: Int = 10
}

public fun schedulerRabbitModule(configure: SchedulerRabbitConfig.() -> Unit): Module {
    val config = SchedulerRabbitConfig().apply(configure)
    val factory = requireNotNull(config.connectionFactory) {
        "schedulerRabbitModule: connectionFactory is required"
    }

    return module {
        single<ConnectionFactory> { factory }
        single<RabbitTopology> { RabbitTopology(config.queues) }
        single<SchedulerRabbitConfig> { config }
        single<JobTransport> {
            RabbitJobTransport(
                factory = get(),
                topology = get(),
                config = get(),
            )
        }
    }
}
