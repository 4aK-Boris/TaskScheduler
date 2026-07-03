@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package cs.trade.scheduler.engine.infra

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import cs.trade.scheduler.core.backend.SchedulerCoreConfig
import cs.trade.scheduler.core.backend.handler.Job
import cs.trade.scheduler.engine.infra.domain.usecases.PublishOutboxBatchUseCase
import cs.trade.scheduler.storage.postgres.infrastructure.PostgresStorageProvider
import cs.trade.scheduler.storage.postgres.infrastructure.repositories.IdempotencyLogRepositoryImpl
import cs.trade.scheduler.storage.postgres.infrastructure.repositories.JobDependencyRepositoryImpl
import cs.trade.scheduler.storage.postgres.infrastructure.repositories.JobEventRepositoryImpl
import cs.trade.scheduler.storage.postgres.infrastructure.repositories.JobRepositoryImpl
import cs.trade.scheduler.storage.postgres.infrastructure.repositories.JobRollupRepositoryImpl
import cs.trade.scheduler.storage.postgres.infrastructure.repositories.JobTypePauseRepositoryImpl
import cs.trade.scheduler.storage.postgres.infrastructure.repositories.OutboxRepositoryImpl
import cs.trade.scheduler.storage.postgres.infrastructure.repositories.RecurringJobRepositoryImpl
import cs.trade.scheduler.storage.postgres.infrastructure.repositories.WorkerRepositoryImpl
import cs.trade.scheduler.storage.postgres.infrastructure.scheduler.DefaultScheduler
import cs.trade.scheduler.transport.rabbit.domain.ConsumerHandle
import cs.trade.scheduler.transport.rabbit.domain.JobTransport
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import org.flywaydb.core.Flyway
import org.jetbrains.exposed.v1.jdbc.Database
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.testcontainers.containers.PostgreSQLContainer
import java.util.Collections
import kotlin.time.Clock
import kotlin.uuid.Uuid

/**
 * Regression test for the outbox head-of-line starvation (prod incident 2026-07-01).
 *
 * Scenario: a paused type keeps enqueueing (recurring trigger doesn't stop on pause) and
 * its rows park in the outbox with `published_at IS NULL`. Before the fix,
 * `findUnpublished(limit)` scanned by id without excluding paused types — once more than
 * a batch-size of parked rows accumulated at the head, every batch consisted only of
 * them, published 0, logged nothing, and every OTHER type starved indefinitely.
 *
 * Asserts:
 *  1. With `batchSize + 20` parked rows of a paused type at the head, one batch tick
 *     still publishes the younger row of a live type (published == 1).
 *  2. Parked rows stay unpublished, and `countUnpublished` reports 0 — pause parking
 *     is not "publisher backlog" (feeds the backlog WARN / lag gauge).
 *  3. Unpause = catch-up: subsequent ticks drain all parked rows (DESIGN.md 22.1).
 *
 * PG-only (no Rabbit) — a recording [JobTransport] fake observes publishes directly.
 * Honours `EXTERNAL_PG_URL` like the sibling suites; falls back to Testcontainers.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class OutboxPauseStarvationTest {

    @Serializable
    data class PausedNoise(val n: Int) : Job

    @Serializable
    data class LiveJob(val n: Int) : Job

    private companion object {
        private val externalUrl: String? = System.getenv("EXTERNAL_PG_URL")?.takeIf { it.isNotBlank() }
        private val PAUSED_TYPE: String = PausedNoise::class.qualifiedName!!
    }

    private lateinit var dataSource: HikariDataSource
    private lateinit var database: Database
    private lateinit var scheduler: DefaultScheduler
    private lateinit var outbox: OutboxRepositoryImpl
    private lateinit var pauses: JobTypePauseRepositoryImpl
    private var postgres: PostgreSQLContainer<*>? = null

    @BeforeAll
    fun setUp() {
        val jdbcUrl: String; val pgUser: String; val pgPass: String
        if (externalUrl != null) {
            jdbcUrl = externalUrl!!
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
            addDataSourceProperty("stringtype", "unspecified")
        })
        Flyway.configure().dataSource(dataSource).locations("classpath:scheduler/migration").load().migrate()
        database = Database.connect(dataSource)
        val jobs = JobRepositoryImpl(database)
        outbox = OutboxRepositoryImpl(database)
        pauses = JobTypePauseRepositoryImpl(database)
        scheduler = DefaultScheduler(
            storage = PostgresStorageProvider(
                jobs = jobs,
                outbox = outbox,
                jobDependencies = JobDependencyRepositoryImpl(database),
                recurringJobs = RecurringJobRepositoryImpl(database),
                jobEvents = JobEventRepositoryImpl(database),
                workers = WorkerRepositoryImpl(database),
                idempotencyLog = IdempotencyLogRepositoryImpl(database),
                jobRollups = JobRollupRepositoryImpl(database),
                jobTypePauses = pauses,
            ),
            database = database,
            config = SchedulerCoreConfig().apply { nodeId = "test-pause-starvation" },
        )
    }

    @AfterAll
    fun tearDown() {
        runCatching { dataSource.close() }
        runCatching { postgres?.stop() }
    }

    /**
     * Empty outbox + no leftover pauses before each test. `job CASCADE` clears the outbox
     * too; job_type_pause has no FK to job so it needs its own truncate. Mirrors
     * [OutboxPublisherLeaderGateTest.cleanTables] for the shared EXTERNAL_PG setup.
     */
    @BeforeEach
    fun cleanTables() {
        dataSource.connection.use { conn ->
            conn.createStatement().use { it.execute("TRUNCATE job RESTART IDENTITY CASCADE") }
            conn.createStatement().use { it.execute("TRUNCATE job_type_pause") }
        }
    }

    @Test
    fun `paused-type rows parked at the head do not starve publishable rows`() = runBlocking {
        val transport = RecordingJobTransport()
        val publishBatch = PublishOutboxBatchUseCase(outbox = outbox, transport = transport)
        val parkedCount = PublishOutboxBatchUseCase.DEFAULT_BATCH_SIZE + 20

        // Pause FIRST, then park batchSize+20 rows of the paused type at the head of the
        // outbox — this is exactly the recurring-keeps-firing-during-a-long-pause shape.
        pauses.pause(PAUSED_TYPE, pausedBy = "test", reason = "starvation regression", pausedSince = Clock.System.now())
        repeat(parkedCount) { scheduler.enqueue(PausedNoise(it)) }
        val liveJobId = scheduler.enqueue(LiveJob(1))

        // One tick. Before the fix: batch = the 100 oldest (all paused) → published 0,
        // the live row never seen. After: paused rows are invisible → the live row goes out.
        val published = publishBatch().getOrThrow()

        assertEquals(1, published, "The single live row must publish through $parkedCount parked rows")
        assertEquals(listOf(liveJobId), transport.publishedJobIds, "Only the live job may reach the transport")
        assertEquals(
            0, outbox.countUnpublished(),
            "Parked rows are not publisher backlog — countUnpublished must exclude paused types",
        )

        // Unpause = catch-up: the parked backlog drains on the following ticks.
        pauses.unpause(PAUSED_TYPE)
        var drained = 0
        repeat(3) { drained += publishBatch().getOrThrow() }

        assertEquals(parkedCount, drained, "All parked rows must publish after unpause")
        assertEquals(0, outbox.countUnpublished(), "Outbox must be fully drained after catch-up")
        assertTrue(
            transport.publishedJobIds.size == parkedCount + 1,
            "Transport must have seen the live job + every parked row exactly once",
        )
    }
}

/** Records every publish; consume() must never be called on the publisher path. */
private class RecordingJobTransport : JobTransport {
    val publishedJobIds: MutableList<Uuid> = Collections.synchronizedList(mutableListOf())

    override suspend fun publish(jobId: Uuid, routingKey: String, priority: Int, delayMillis: Long) {
        publishedJobIds.add(jobId)
    }
    override suspend fun consume(
        queue: String,
        prefetch: Int,
        handler: suspend (jobId: Uuid) -> Unit,
    ): ConsumerHandle = error("not used in publisher-only test")
    override suspend fun cancelAllConsumers() {}
    override suspend fun close() {}
}
