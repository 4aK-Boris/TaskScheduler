@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package cs.trade.scheduler.storage.postgres

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import cs.trade.scheduler.core.backend.Scheduler
import cs.trade.scheduler.core.backend.handler.Job
import cs.trade.scheduler.core.backend.schedulerCoreModule
import cs.trade.scheduler.shared.JobState
import cs.trade.scheduler.storage.postgres.domain.repositories.JobRepository
import cs.trade.scheduler.storage.postgres.domain.repositories.OutboxRepository
import cs.trade.scheduler.storage.postgres.infrastructure.schedulerPostgresModule
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.KoinAppDeclaration
import org.testcontainers.containers.PostgreSQLContainer

/**
 * End-to-end enqueue test against a real Postgres (Testcontainers).
 *
 * Boots: PG 16 → Hikari pool → Flyway migrate (V1) → Koin graph
 * (schedulerCoreModule + schedulerPostgresModule) → resolve [Scheduler] → call enqueue.
 *
 * Verifies:
 *   1. Returned job UUID is found in `job` with state ENQUEUED and the right payload.
 *   2. Exactly one outbox row points at the new job, unpublished.
 *
 * **PG provisioning.** Honours `EXTERNAL_PG_URL` so CI can point at a shared
 * scheduler-test-pg without spinning a per-class Testcontainer. When the env var is
 * absent, falls back to `PostgreSQLContainer("postgres:16-alpine")`. Lifecycle is
 * managed manually (no `@Testcontainers` / `@Container`) — the annotation eagerly
 * resolves Docker at class-load time and the env override would never get a chance
 * to short-circuit it.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class EnqueueIntegrationTest {

    @Serializable
    data class TestSendEmail(val userId: Long, val template: String) : Job

    private companion object {
        private val externalUrl: String? = System.getenv("EXTERNAL_PG_URL")?.takeIf { it.isNotBlank() }
    }

    private lateinit var dataSource: HikariDataSource
    private var postgres: PostgreSQLContainer<*>? = null

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

        dataSource = HikariDataSource(HikariConfig().apply {
            this.jdbcUrl = jdbcUrl
            username = pgUser
            password = pgPass
            maximumPoolSize = 4
            // PgJDBC sends String parameters as VARCHAR by default — INSERT into a JSONB
            // column (payload_json, context_json) then fails with "expression is of type
            // character varying". `unspecified` lets Postgres infer the type from the
            // target column (DESIGN.md 14.2).
            addDataSourceProperty("stringtype", "unspecified")
        })

        val app: KoinAppDeclaration = {
            modules(
                schedulerCoreModule { nodeId = "test" },
                schedulerPostgresModule {
                    this.dataSource = this@EnqueueIntegrationTest.dataSource
                    runMigrations = true
                },
            )
        }
        startKoin(app)
    }

    @AfterAll
    fun tearDown() {
        stopKoin()
        runCatching { dataSource.close() }
        runCatching { postgres?.stop() }
    }

    @Test
    fun `enqueue persists job row in ENQUEUED state and one unpublished outbox row`() = runBlocking {
        val koin = org.koin.core.context.GlobalContext.get()
        val scheduler = koin.get<Scheduler>()
        val jobs = koin.get<JobRepository>()
        val outbox = koin.get<OutboxRepository>()

        val jobId = scheduler.enqueue(TestSendEmail(userId = 123L, template = "welcome"))

        val saved = jobs.findById(jobId)
        assertNotNull(saved, "Job should be persisted")
        assertEquals(JobState.ENQUEUED, saved!!.state)
        assertEquals("default", saved.queue)
        assertEquals(TestSendEmail::class.qualifiedName, saved.payloadType)
        // PG normalises JSONB on storage — keys keep their colons but values are spaced
        // (`{"userId": 123, "template": "welcome"}`). Match with whitespace-tolerant regex so
        // the assertion survives both the in-memory `kotlinx.serialization.json` shape
        // (compact, no spaces) and the PG round-trip shape.
        assertTrue(
            Regex("\"userId\"\\s*:\\s*123").containsMatchIn(saved.payloadJson),
            "payload_json must contain userId=123: ${saved.payloadJson}",
        )
        assertTrue(
            Regex("\"template\"\\s*:\\s*\"welcome\"").containsMatchIn(saved.payloadJson),
            "payload_json must contain template=welcome: ${saved.payloadJson}",
        )
        assertEquals(0, saved.attempts)
        assertEquals(3, saved.maxAttempts)
        assertNull(saved.lockedBy)

        // Scope assertions to our own jobId — under EXTERNAL_PG_URL the test reuses a
        // shared `scheduler-test-pg`, so other suites' unpublished rows can be in here.
        // We care that THIS enqueue produced exactly one outbox row, not that the global
        // outbox is empty.
        val ours = outbox.findUnpublished(limit = 1000).filter { it.jobId == jobId }
        assertEquals(1, ours.size, "Exactly one outbox row expected for jobId=$jobId")
        assertEquals("default", ours[0].routingKey)
        assertNull(ours[0].publishedAt)
    }
}
