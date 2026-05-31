package cs.trade.scheduler.transport.rabbit.infrastructure

import com.rabbitmq.client.ConnectionFactory
import cs.trade.scheduler.transport.rabbit.domain.JobTransport
import org.koin.core.module.Module
import org.koin.dsl.module
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

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

    /**
     * Backoff between an unexpected consumer-channel death (e.g. `406 PRECONDITION_FAILED` from
     * `consumer_timeout`) and the automatic re-subscribe in [RabbitJobTransport]. Also the retry
     * interval if a re-subscribe attempt itself throws. Kept out of the hot path; tests lower it.
     */
    public var reconnectDelay: Duration = 5.seconds
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
