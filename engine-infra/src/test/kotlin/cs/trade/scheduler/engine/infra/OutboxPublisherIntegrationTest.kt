@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package cs.trade.scheduler.engine.infra

import com.rabbitmq.client.ConnectionFactory
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import cs.trade.scheduler.core.backend.Scheduler
import cs.trade.scheduler.core.backend.handler.Job
import cs.trade.scheduler.core.backend.schedulerCoreModule
import cs.trade.scheduler.engine.infra.domain.usecases.PublishOutboxBatchUseCase
import cs.trade.scheduler.engine.infra.infrastructure.schedulerInfraModule
import cs.trade.scheduler.storage.postgres.domain.repositories.OutboxRepository
import cs.trade.scheduler.storage.postgres.infrastructure.schedulerPostgresModule
import cs.trade.scheduler.transport.rabbit.domain.JobTransport
import cs.trade.scheduler.transport.rabbit.infrastructure.schedulerRabbitModule
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.koin.core.context.GlobalContext
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.containers.RabbitMQContainer
import org.testcontainers.utility.DockerImageName

/**
 * End-to-end PG outbox → Rabbit publish.
 *
 * Boots a real Postgres and a real RabbitMQ (with the delayed-message plugin
 * pre-installed — needed because [RabbitTopology] declares `jobs.dispatch` as
 * `x-delayed-message`). Asserts that:
 *
 *   1. `Scheduler.enqueue` writes one unpublished outbox row.
 *   2. One tick of [PublishOutboxBatchUseCase] returns 1 published.
 *   3. The outbox row's `published_at` flips non-null.
 *   4. A message arrives on `q.default` with body = the job's 16 UUID bytes.
 *
 * **PG provisioning.** Honours `EXTERNAL_PG_URL` for the shared scheduler-test-pg
 * setup; falls back to Testcontainers when absent. Rabbit always uses Testcontainers
 * since there's no analogous bypass. Manual lifecycle so the env override can
 * short-circuit Docker for PG.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class OutboxPublisherIntegrationTest {

    @Serializable
    data class TestSendEmail(val userId: Long, val template: String) : Job

    private companion object {
        // Pre-built image with rabbitmq_delayed_message_exchange enabled; we declare
        // the dispatch exchange as `x-delayed-message`, which fails on stock images.
        private val rabbitImage: DockerImageName =
            DockerImageName.parse("heidiks/rabbitmq-delayed-message-exchange:3.13.0-management")
                .asCompatibleSubstituteFor("rabbitmq")

        private val externalUrl: String? = System.getenv("EXTERNAL_PG_URL")?.takeIf { it.isNotBlank() }
    }

    private lateinit var dataSource: HikariDataSource
    private lateinit var connectionFactory: ConnectionFactory
    private var postgres: PostgreSQLContainer<*>? = null
    private lateinit var rabbit: RabbitMQContainer

    @BeforeAll
    fun setUp() {
        runCatching { stopKoin() }
        val jdbcUrl: String; val pgUser: String; val pgPass: String
        if (externalUrl != null) {
            jdbcUrl = externalUrl
            pgUser = System.getenv("EXTERNAL_PG_USER") ?: "scheduler"
            pgPass = System.getenv("EXTERNAL_PG_PASSWORD") ?: "scheduler"
        } else {
            val tc = PostgreSQLContainer("postgres:16-alpine")
                .withDatabaseName("scheduler")
                .withUsername("scheduler")
                .withPassword("scheduler")
            tc.start()
            postgres = tc
            jdbcUrl = tc.jdbcUrl
            pgUser = tc.username
            pgPass = tc.password
        }
        rabbit = RabbitMQContainer(rabbitImage)
        rabbit.start()

        dataSource = HikariDataSource(HikariConfig().apply {
            this.jdbcUrl = jdbcUrl
            username = pgUser
            password = pgPass
            maximumPoolSize = 4
            addDataSourceProperty("stringtype", "unspecified")
        })

        connectionFactory = ConnectionFactory().apply {
            host = rabbit.host
            port = rabbit.amqpPort
            username = rabbit.adminUsername
            password = rabbit.adminPassword
        }

        startKoin {
            modules(
                schedulerCoreModule { nodeId = "test" },
                schedulerPostgresModule {
                    this.dataSource = this@OutboxPublisherIntegrationTest.dataSource
                    runMigrations = true
                },
                schedulerRabbitModule {
                    connectionFactory = this@OutboxPublisherIntegrationTest.connectionFactory
                    queues = listOf("default")
                },
                schedulerInfraModule(),
            )
        }
    }

    @AfterAll
    fun tearDown() {
        // Best-effort close — even if it throws, we still want to stop containers.
        runCatching { runBlocking { GlobalContext.get().get<JobTransport>().close() } }
        runCatching { stopKoin() }
        runCatching { dataSource.close() }
        runCatching { rabbit.stop() }
        runCatching { postgres?.stop() }
    }

    @Test
    fun `outbox publisher pushes one job to q dot default and marks row published`() = runBlocking {
        val koin = GlobalContext.get()
        val scheduler = koin.get<Scheduler>()
        val outbox = koin.get<OutboxRepository>()
        val publishBatch = koin.get<PublishOutboxBatchUseCase>()

        val jobId = scheduler.enqueue(TestSendEmail(userId = 42L, template = "welcome"))

        // Before tick: 1 unpublished row.
        assertEquals(1, outbox.findUnpublished(limit = 10).size)

        val published = publishBatch().getOrThrow()
        assertEquals(1, published, "PublishOutboxBatchUseCase should publish exactly 1 row")

        // After tick: 0 unpublished.
        assertEquals(0, outbox.findUnpublished(limit = 10).size)

        // Pull the message out of q.default and verify the body equals the job UUID bytes.
        // A short retry loop covers delayed-message plugin's "publish through delayed
        // exchange even with x-delay absent" latency (typically <100ms).
        val received = pollQueueForBody(qName = "q.default", attempts = 50, sleepMs = 100)
        assertNotNull(received, "Expected a message on q.default within 5s")
        assertEquals(16, received!!.size)
        assertArrayEquals(jobId.toByteArray(), received)
    }

    private fun pollQueueForBody(qName: String, attempts: Int, sleepMs: Long): ByteArray? {
        connectionFactory.newConnection("test-consumer").use { conn ->
            conn.createChannel().use { ch ->
                repeat(attempts) {
                    val resp = ch.basicGet(qName, /* autoAck = */ true)
                    if (resp != null) return resp.body
                    Thread.sleep(sleepMs)
                }
            }
        }
        return null
    }

}
