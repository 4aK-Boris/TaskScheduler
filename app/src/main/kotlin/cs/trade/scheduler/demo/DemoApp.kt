@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package cs.trade.scheduler.demo

import com.rabbitmq.client.ConnectionFactory
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import cs.trade.scheduler.core.backend.Scheduler
import cs.trade.scheduler.core.backend.handler.JobHandler
import cs.trade.scheduler.core.backend.handler.retry.ExponentialBackoff
import cs.trade.scheduler.core.backend.schedulerCoreModule
import cs.trade.scheduler.engine.worker.infrastructure.WorkerPool
import cs.trade.scheduler.engine.worker.infrastructure.schedulerWorkerModule
import cs.trade.scheduler.storage.postgres.infrastructure.schedulerPostgresModule
import cs.trade.scheduler.transport.rabbit.domain.JobTransport
import cs.trade.scheduler.transport.rabbit.infrastructure.schedulerRabbitModule
import kotlinx.coroutines.runBlocking
import org.koin.core.context.GlobalContext
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.bind
import org.koin.dsl.module
import org.koin.logger.slf4jLogger
import org.slf4j.LoggerFactory
import kotlin.time.Duration.Companion.seconds

/**
 * Demo user-app entry point. Boots a WorkerPool that consumes the `default` queue and
 * enqueues a single SendEmail job to prove the wiring works end-to-end.
 *
 * Requires the scheduler-infra container + postgres + rabbit to be up first
 * (`docker compose up -d`).
 */
private val log = LoggerFactory.getLogger("cs.trade.scheduler.demo.DemoApp")

public fun main(): Unit = runBlocking {
    val pgUrl = System.getenv("POSTGRES_URL") ?: "jdbc:postgresql://localhost:5432/scheduler"
    val pgUser = System.getenv("POSTGRES_USER") ?: "scheduler"
    val pgPass = System.getenv("POSTGRES_PASSWORD") ?: "scheduler"
    val rabbitHost = System.getenv("RABBITMQ_HOST") ?: "localhost"
    val rabbitUser = System.getenv("RABBITMQ_USER") ?: "scheduler"
    val rabbitPass = System.getenv("RABBITMQ_PASSWORD") ?: "scheduler"

    val ds = HikariDataSource(HikariConfig().apply {
        jdbcUrl = pgUrl
        username = pgUser
        password = pgPass
        addDataSourceProperty("stringtype", "unspecified")
    })

    val rabbit = ConnectionFactory().apply {
        host = rabbitHost
        username = rabbitUser
        password = rabbitPass
        isAutomaticRecoveryEnabled = true
    }

    startKoin {
        slf4jLogger()
        modules(
            schedulerCoreModule {
                nodeId = "demo-app-1"
                defaultRetryPolicy = ExponentialBackoff(maxAttempts = 3)
            },
            schedulerPostgresModule {
                dataSource = ds
                runMigrations = false   // infra owns the schema
            },
            schedulerRabbitModule {
                connectionFactory = rabbit
                queues = listOf("default")
            },
            schedulerWorkerModule {
                nodeId = "demo-app-1"
                lockDuration = 60.seconds
                queue("default", concurrency = 5)
            },
            // User-side handler bean. Manually bound because we want to keep DemoApp
            // free of @ComponentScan plumbing — the real app would auto-discover via
            // Koin annotations.
            module { single { SendEmailHandler() } bind JobHandler::class },
        )
    }

    val koin = GlobalContext.get()
    val workerPool = koin.get<WorkerPool>()
    val scheduler = koin.get<Scheduler>()

    workerPool.start()
    log.info("DemoApp worker started. Enqueueing one SendEmail to prove end-to-end wiring…")

    val jobId = scheduler.enqueue(SendEmail(userId = 42L, template = "welcome"))
    log.info("Enqueued jobId={} — watch the handler log line below", jobId)

    Runtime.getRuntime().addShutdownHook(Thread {
        log.info("Shutdown hook: stopping WorkerPool, Rabbit, DataSource")
        runCatching { runBlocking { workerPool.stop() } }
        runCatching { runBlocking { koin.get<JobTransport>().close() } }
        runCatching { stopKoin() }
        runCatching { ds.close() }
    })

    // Keep the JVM alive so the consumer thread can run the handler.
    Thread.currentThread().join()
}
