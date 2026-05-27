@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package cs.trade.scheduler.engine.infra

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import cs.trade.scheduler.core.backend.archival.ArchivalSink
import cs.trade.scheduler.engine.infra.domain.usecases.RetentionCleanupBatchUseCase
import cs.trade.scheduler.engine.infra.infrastructure.SchedulerInfraConfig
import cs.trade.scheduler.shared.archival.ArchivedJobRecord
import cs.trade.scheduler.shared.JobPriority
import cs.trade.scheduler.shared.JobState
import cs.trade.scheduler.storage.postgres.domain.models.Job
import cs.trade.scheduler.storage.postgres.domain.models.NewOutboxEntry
import cs.trade.scheduler.storage.postgres.infrastructure.repositories.IdempotencyLogRepositoryImpl
import cs.trade.scheduler.storage.postgres.infrastructure.repositories.JobRepositoryImpl
import cs.trade.scheduler.storage.postgres.infrastructure.repositories.OutboxRepositoryImpl
import cs.trade.scheduler.storage.postgres.infrastructure.tables.IdempotencyLogTable
import cs.trade.scheduler.storage.postgres.infrastructure.tables.JobTable
import cs.trade.scheduler.storage.postgres.infrastructure.tables.OutboxTable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.flywaydb.core.Flyway
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.suspendTransaction
import org.jetbrains.exposed.v1.jdbc.update
import java.time.ZoneOffset
import kotlin.time.toJavaInstant
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.testcontainers.containers.PostgreSQLContainer
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.uuid.Uuid

/**
 * Verifies retention cleanup: terminal job rows past their per-state TTL get deleted,
 * recent rows survive, and published outbox rows past their TTL get nuked while
 * unpublished ones stay.
 *
 * Direct repo wiring (no Rabbit, no Koin) — the UseCase only needs JobRepository +
 * OutboxRepository + SchedulerInfraConfig.
 *
 * **PG provisioning.** Honours `EXTERNAL_PG_URL` (same convention as
 * [OutboxPublisherLeaderGateTest] / [JobRepositoryCasIntegrationTest]) so CI can point
 * at a shared scheduler-test-pg without spinning a per-class Testcontainer. When the env
 * var is absent, falls back to `PostgreSQLContainer("postgres:16-alpine")` for local
 * Docker-equipped dev boxes. Lifecycle is managed manually (no `@Testcontainers` /
 * `@Container`) — the annotation eagerly resolves Docker at class-load time and the env
 * override would never get a chance to short-circuit it.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RetentionIntegrationTest {

    private companion object {
        private val externalUrl: String? = System.getenv("EXTERNAL_PG_URL")?.takeIf { it.isNotBlank() }
    }

    private lateinit var dataSource: HikariDataSource
    private lateinit var database: Database
    private lateinit var jobs: JobRepositoryImpl
    private lateinit var outbox: OutboxRepositoryImpl
    private lateinit var idempotencyLog: IdempotencyLogRepositoryImpl
    private lateinit var capturingSink: CapturingArchivalSink
    private lateinit var infraConfig: SchedulerInfraConfig
    private lateinit var useCase: RetentionCleanupBatchUseCase
    private var postgres: PostgreSQLContainer<*>? = null

    @BeforeAll
    fun setUp() {
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
            addDataSourceProperty("stringtype", "unspecified")
        })
        Flyway.configure().dataSource(dataSource).load().migrate()

        database = Database.connect(dataSource)
        jobs = JobRepositoryImpl(database)
        outbox = OutboxRepositoryImpl(database)
        idempotencyLog = IdempotencyLogRepositoryImpl(database)
        val workers = cs.trade.scheduler.storage.postgres.infrastructure.repositories.WorkerRepositoryImpl(database)
        infraConfig = SchedulerInfraConfig().apply {
            // Tight TTLs for testing — anything older than 1h goes.
            retention.succeeded = 1.hours
            retention.failed = 1.hours
            retention.cancelled = 1.hours
            retention.outboxPublished = 1.hours
            retention.idempotencyLog = 1.hours
            // deadWorkers default = 1.days; explicit for clarity
        }
        capturingSink = CapturingArchivalSink()
        useCase = RetentionCleanupBatchUseCase(
            jobs = jobs,
            outbox = outbox,
            workers = workers,
            idempotencyLog = idempotencyLog,
            archivalSink = capturingSink,
            config = infraConfig,
        )
    }

    @AfterAll
    fun tearDown() {
        runCatching { dataSource.close() }
        // postgres is null when EXTERNAL_PG_URL provisioned the DB (we don't own its
        // lifecycle). Only stop the container we actually started.
        runCatching { postgres?.stop() }
    }

    @Test
    fun `deletes terminal rows past retention and keeps recent ones`() = runBlocking {
        val now = Clock.System.now()
        val oldSucceeded = insertJob(state = JobState.SUCCEEDED, updatedAt = now - 2.days)
        val recentSucceeded = insertJob(state = JobState.SUCCEEDED, updatedAt = now - 30.minutes)
        val oldFailed = insertJob(state = JobState.FAILED, updatedAt = now - 2.days)
        val recentEnqueued = insertJob(state = JobState.ENQUEUED, updatedAt = now - 2.days)

        val deleted = useCase().getOrThrow()
        assertTrue(deleted >= 2, "Expected at least 2 deletions (oldSucceeded + oldFailed), got $deleted")

        assertNull(jobs.findById(oldSucceeded), "Old SUCCEEDED should be gone")
        assertNull(jobs.findById(oldFailed), "Old FAILED should be gone")
        assertNotNull(jobs.findById(recentSucceeded), "Recent SUCCEEDED must survive")
        assertNotNull(jobs.findById(recentEnqueued), "Non-terminal ENQUEUED must survive regardless of age")
    }

    @Test
    fun `null retention disables that bucket`() = runBlocking {
        val saved = infraConfig.retention.failed
        try {
            infraConfig.retention.failed = null
            val now = Clock.System.now()
            val oldFailed = insertJob(state = JobState.FAILED, updatedAt = now - 100.days)

            useCase().getOrThrow()

            assertNotNull(
                jobs.findById(oldFailed),
                "Disabled retention bucket should not touch the row",
            )
        } finally {
            infraConfig.retention.failed = saved
        }
    }

    @Test
    fun `archival sink receives terminal rows BEFORE delete and rows survive on sink failure`() = runBlocking {
        val now = Clock.System.now()
        val sucId = insertJob(state = JobState.SUCCEEDED, updatedAt = now - 2.days)

        // First: happy path — sink captures, row deleted.
        capturingSink.reset()
        useCase().getOrThrow()
        val captured = capturingSink.archived["job.succeeded"].orEmpty()
        assertTrue(captured.any { it.id == sucId.toString() }, "Sink must see the row before DELETE")
        assertNull(jobs.findById(sucId), "Row should be gone after archive+delete")

        // Second: failing sink — row sticks around so the next tick can retry.
        val failId = insertJob(state = JobState.FAILED, updatedAt = now - 2.days)
        capturingSink.failNextWith = RuntimeException("S3 timeout (test)")
        useCase().getOrThrow()
        assertNotNull(
            jobs.findById(failId),
            "Row must survive a sink failure — retention skips DELETE so a later tick can retry",
        )

        // Third: same row gets archived + deleted on the next tick once the sink recovers.
        capturingSink.reset()
        useCase().getOrThrow()
        val capturedFailed = capturingSink.archived["job.failed"].orEmpty()
        assertTrue(capturedFailed.any { it.id == failId.toString() })
        assertNull(jobs.findById(failId))
    }

    @Test
    fun `idempotency log tryMark is race-free and dedup-correct`() = runBlocking {
        val jobId = Uuid.random()
        // First mark wins.
        assertTrue(idempotencyLog.tryMark(jobId, "charge"), "first tryMark must return true")
        // Duplicate on same (jobId, action) → false.
        assertEquals(
            false,
            idempotencyLog.tryMark(jobId, "charge"),
            "second tryMark on same key must return false (duplicate dedup)",
        )
        // Different action on same job → independent, wins.
        assertTrue(
            idempotencyLog.tryMark(jobId, "notify"),
            "different action on same jobId must succeed (multi-step semantics)",
        )

        val entries = idempotencyLog.findByJobId(jobId)
        assertEquals(2, entries.size, "expected 2 marks (charge + notify), got $entries")
    }

    @Test
    fun `deletes idempotency log rows past retention and keeps recent ones`() = runBlocking {
        val now = Clock.System.now()
        val oldJobId = Uuid.random()
        val recentJobId = Uuid.random()

        idempotencyLog.tryMark(oldJobId, "step1")
        idempotencyLog.tryMark(recentJobId, "step1")

        // tryMark stamps occurred_at = now() via Clock.System.now() inside the impl —
        // backdate oldJobId's row so the 1h retention predicate has something to delete.
        backdateIdempotencyOccurredAt(oldJobId, now - 2.days)

        useCase().getOrThrow()

        assertTrue(
            idempotencyLog.findByJobId(oldJobId).isEmpty(),
            "Old idempotency mark should be gone",
        )
        assertEquals(
            1,
            idempotencyLog.findByJobId(recentJobId).size,
            "Recent idempotency mark must survive",
        )
    }

    @Test
    fun `deletes published outbox rows past retention but keeps unpublished`() = runBlocking {
        val now = Clock.System.now()
        // FK to job — need real parent rows.
        val jobAId = insertJob(state = JobState.ENQUEUED, updatedAt = now)
        val jobBId = insertJob(state = JobState.ENQUEUED, updatedAt = now)
        val pendingId = outbox.insert(NewOutboxEntry(jobAId, "default", 0, 0)).id
        val toPublishId = outbox.insert(NewOutboxEntry(jobBId, "default", 0, 0)).id
        outbox.markPublished(toPublishId)
        backdateOutboxPublished(toPublishId, now - 2.days)

        useCase().getOrThrow()

        val remaining = outbox.findUnpublished(limit = 100)
        assertTrue(
            remaining.any { it.id == pendingId },
            "Unpublished outbox row must survive",
        )

        val staleRow = withContext(Dispatchers.IO) {
            suspendTransaction(db = database) {
                OutboxTable.selectAll().where { OutboxTable.id eq toPublishId }.firstOrNull()
            }
        }
        assertNull(staleRow, "Old published outbox row must be deleted")
    }

    private suspend fun insertJob(
        state: JobState,
        updatedAt: kotlin.time.Instant,
    ): Uuid {
        val jobId = Uuid.random()
        jobs.insert(
            Job(
                id = jobId,
                state = state,
                queue = "default",
                priority = JobPriority(0),
                payloadType = "test.RetentionJob",
                payloadJson = "{}",
                scheduledAt = null,
                attempts = 0,
                maxAttempts = 3,
                timeoutSeconds = null,
                lockedBy = null,
                lockedUntil = null,
                pendingDeps = 0,
                version = 0,
                idempotencyKey = null,
                targetNode = null,
                targetTag = null,
                progress = null,
                progressMsg = null,
                progressUpdatedAt = null,
                startedAt = null,
                durationMs = null,
                cancelRequestedAt = null,
                cancelRequestedBy = null,
                contextJson = null,
                createdAt = updatedAt,
                updatedAt = updatedAt,
            ),
        )
        // insert() stamps `updatedAt = Clock.now()` regardless of what we pass — backdate
        // it manually so the retention "older than" predicate has something to match.
        backdateJobUpdatedAt(jobId, updatedAt)
        return jobId
    }

    private suspend fun backdateJobUpdatedAt(jobId: Uuid, target: kotlin.time.Instant) {
        val odt = target.toJavaInstant().atOffset(ZoneOffset.UTC)
        withContext(Dispatchers.IO) {
            suspendTransaction(db = database) {
                JobTable.update({ JobTable.id eq jobId }) { it[updatedAt] = odt }
            }
        }
    }

    private suspend fun backdateOutboxPublished(outboxId: Long, target: kotlin.time.Instant) {
        val odt = target.toJavaInstant().atOffset(ZoneOffset.UTC)
        withContext(Dispatchers.IO) {
            suspendTransaction(db = database) {
                OutboxTable.update({ OutboxTable.id eq outboxId }) { it[publishedAt] = odt }
            }
        }
    }

    /** In-memory archival sink for tests: captures everything, optionally fails on demand. */
    private class CapturingArchivalSink : ArchivalSink {
        val archived: MutableMap<String, MutableList<ArchivedJobRecord>> = mutableMapOf()
        var failNextWith: Throwable? = null

        override suspend fun archive(category: String, batch: List<ArchivedJobRecord>) {
            failNextWith?.let { e ->
                failNextWith = null  // arm only one failure per set — next call succeeds
                throw e
            }
            archived.getOrPut(category) { mutableListOf() }.addAll(batch)
        }

        fun reset() {
            archived.clear()
            failNextWith = null
        }
    }

    private suspend fun backdateIdempotencyOccurredAt(jobId: Uuid, target: kotlin.time.Instant) {
        val odt = target.toJavaInstant().atOffset(ZoneOffset.UTC)
        withContext(Dispatchers.IO) {
            suspendTransaction(db = database) {
                IdempotencyLogTable.update({ IdempotencyLogTable.jobId eq jobId }) {
                    it[occurredAt] = odt
                }
            }
        }
    }
}
