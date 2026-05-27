@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package cs.trade.scheduler.engine.worker

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import cs.trade.scheduler.core.backend.EnqueueOptions
import cs.trade.scheduler.core.backend.SchedulerCoreConfig
import cs.trade.scheduler.core.backend.handler.Job
import cs.trade.scheduler.shared.CancelResult
import cs.trade.scheduler.shared.JobState
import cs.trade.scheduler.shared.OnFailure
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
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import org.flywaydb.core.Flyway
import org.jetbrains.exposed.v1.jdbc.Database
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.testcontainers.containers.PostgreSQLContainer

/**
 * DESIGN.md 8.4 — cancelling a parent must propagate to descendants still in
 * AWAITING_DEPS, following the same `on_failure` rule used by the FAILED cascade:
 *   - `PROPAGATE_FAILURE` / `CANCEL_CHILD` → child also CANCELLED, recursive
 *   - `IGNORE` → child untouched (operator opt-out)
 *
 * Without this, children sit forever in AWAITING_DEPS once their parent gets cancelled
 * (the parent's terminal state is reachable, but the dep-counter never decrements because
 * cancel doesn't go through `FinalizeJobUseCase`).
 *
 * **PG provisioning.** Honours `EXTERNAL_PG_URL` for the shared scheduler-test-pg
 * setup; falls back to Testcontainers when absent. Manual lifecycle so the env
 * override can short-circuit Docker.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CancellationPropagationIntegrationTest {

    @Serializable data class Root(val n: Long) : Job
    @Serializable data class A(val n: Long) : Job
    @Serializable data class A1(val n: Long) : Job
    @Serializable data class B(val n: Long) : Job
    @Serializable data class C(val n: Long) : Job

    private companion object {
        private val externalUrl: String? = System.getenv("EXTERNAL_PG_URL")?.takeIf { it.isNotBlank() }
        private const val ACTOR = "test-actor"
    }

    private lateinit var dataSource: HikariDataSource
    private lateinit var database: Database
    private lateinit var jobs: JobRepositoryImpl
    private lateinit var jobEvents: JobEventRepositoryImpl
    private lateinit var scheduler: DefaultScheduler
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
        jobEvents = JobEventRepositoryImpl(database)
        // Wire jobEvents so we can assert CASCADE_CANCELLED rows landed in the audit log.
        jobs = JobRepositoryImpl(database, events = jobEvents)
        val outbox = OutboxRepositoryImpl(database)
        scheduler = DefaultScheduler(
            storage = PostgresStorageProvider(
                jobs = jobs,
                outbox = outbox,
                jobDependencies = JobDependencyRepositoryImpl(database),
                recurringJobs = RecurringJobRepositoryImpl(database),
                jobEvents = jobEvents,
                workers = WorkerRepositoryImpl(database),
                idempotencyLog = IdempotencyLogRepositoryImpl(database),
                jobRollups = JobRollupRepositoryImpl(database),
                jobTypePauses = JobTypePauseRepositoryImpl(database),
            ),
            database = database,
            config = SchedulerCoreConfig().apply { nodeId = "test-cancel-propagation" },
            events = jobEvents,
        )
    }

    @AfterAll
    fun tearDown() {
        runCatching { dataSource.close() }
        runCatching { postgres?.stop() }
    }

    @Test
    fun `cancelling root cascades to AWAITING_DEPS descendants, skips IGNORE edges`() = runBlocking {
        // DAG shape:
        //   root ──PROPAGATE_FAILURE──► A ──PROPAGATE_FAILURE──► A1
        //   root ──PROPAGATE_FAILURE──► B
        //   root ──IGNORE──► C
        //
        // After cancel(root):
        //   root, A, A1, B → CANCELLED (and cancel_requested_by = test-actor on cascaded rows)
        //   C            → still AWAITING_DEPS (IGNORE edge opts out)
        val rootId = scheduler.enqueue(Root(1))
        val aId = scheduler.enqueueAfter(
            job = A(1),
            waitFor = listOf(rootId),
            options = EnqueueOptions(onParentFailure = OnFailure.PROPAGATE_FAILURE),
        )
        val a1Id = scheduler.enqueueAfter(
            job = A1(1),
            waitFor = listOf(aId),
            options = EnqueueOptions(onParentFailure = OnFailure.PROPAGATE_FAILURE),
        )
        val bId = scheduler.enqueueAfter(
            job = B(1),
            waitFor = listOf(rootId),
            options = EnqueueOptions(onParentFailure = OnFailure.PROPAGATE_FAILURE),
        )
        val cId = scheduler.enqueueAfter(
            job = C(1),
            waitFor = listOf(rootId),
            options = EnqueueOptions(onParentFailure = OnFailure.IGNORE),
        )

        // Sanity: all children start in AWAITING_DEPS.
        listOf(aId, a1Id, bId, cId).forEach {
            assertEquals(JobState.AWAITING_DEPS, jobs.findById(it)!!.state, "child $it should start AWAITING_DEPS")
        }

        val result = scheduler.cancel(rootId, by = ACTOR)
        assertEquals(CancelResult.CANCELLED, result)

        // Root row + audit. The root went through `markCancelled` (non-PROCESSING path),
        // which records the actor in the MANUAL_CANCELLED event but does NOT stamp the
        // `cancel_requested_by` column on the row — that column is reserved for the
        // PROCESSING-row "cooperative-cancel" handshake. Same convention as the existing
        // CancelIntegrationTest.
        val root = jobs.findById(rootId)!!
        assertEquals(JobState.CANCELLED, root.state)
        val rootCancelEvent = jobEvents.findByJobId(rootId).single { it.eventType == "MANUAL_CANCELLED" }
        assertEquals(ACTOR, rootCancelEvent.actor, "root cancel must record the actor in the audit event")

        // Cascaded descendants — A, A1, B all CANCELLED with actor stamped.
        for (id in listOf(aId, a1Id, bId)) {
            val row = jobs.findById(id)!!
            assertEquals(
                JobState.CANCELLED, row.state,
                "child $id should be CANCELLED via cascade (PROPAGATE_FAILURE edge)",
            )
            assertEquals(
                ACTOR, row.cancelRequestedBy,
                "child $id should have cancel_requested_by = $ACTOR (audit attribution)",
            )
            assertNotNull(row.cancelRequestedAt, "child $id should have cancel_requested_at stamped")
            // Audit row recorded with CASCADE_CANCELLED type so the dashboard timeline
            // distinguishes a cascaded cancel from a direct MANUAL_CANCELLED.
            val cascadeEvents = jobEvents.findByJobId(id).filter { it.eventType == "CASCADE_CANCELLED" }
            assertEquals(1, cascadeEvents.size, "child $id should have one CASCADE_CANCELLED audit event")
            assertEquals(ACTOR, cascadeEvents.single().actor)
        }

        // IGNORE edge — C must stay put. The cancel didn't follow this edge, so C still
        // sits AWAITING_DEPS forever (until either someone manually cancels it or the
        // operator decides what to do — that's the whole point of IGNORE).
        val c = jobs.findById(cId)!!
        assertEquals(
            JobState.AWAITING_DEPS, c.state,
            "IGNORE edge means cancel must NOT propagate to C — operator opt-out",
        )
        assertNull(c.cancelRequestedBy, "C must NOT have cancel_requested_by stamped")
    }

    @Test
    fun `cancelling a leaf returns CANCELLED with no descendants affected`() = runBlocking {
        // No children — cascade is a no-op (count = 0 internally). Same user-facing
        // CancelResult.CANCELLED as a parent cancel, no observable difference.
        val leafId = scheduler.enqueue(Root(2))

        val result = scheduler.cancel(leafId, by = ACTOR)
        assertEquals(CancelResult.CANCELLED, result)

        val leaf = jobs.findById(leafId)!!
        assertEquals(JobState.CANCELLED, leaf.state)

        // Bulk-cancel helper should report 0 when called directly on a leaf — covers the
        // empty-DAG branch of the BFS.
        val count = jobs.cancelDescendantsAwaitingDeps(leafId, by = ACTOR)
        assertEquals(0, count, "leaf has no descendants — cascade count must be 0")
    }

    @Test
    fun `cascade respects pre-existing state — children already past AWAITING_DEPS are untouched`() = runBlocking {
        // Build root → child where the child has ALREADY been promoted to ENQUEUED
        // (simulated by transitioning state directly). When we cancel the root, the
        // child is no longer in AWAITING_DEPS so the cascade must leave it alone —
        // ENQUEUED rows are operator-visible and cancellable individually.
        val rootId = scheduler.enqueue(Root(3))
        val childId = scheduler.enqueueAfter(
            job = A(3),
            waitFor = listOf(rootId),
            options = EnqueueOptions(onParentFailure = OnFailure.PROPAGATE_FAILURE),
        )
        // Force-promote the child to ENQUEUED. Real life: a different parent of a fan-in
        // child resolved first, this one just got its dep resolved separately, etc.
        val childVersion = jobs.findById(childId)!!.version
        assertTrue(
            jobs.transitionState(
                id = childId,
                expectedVersion = childVersion,
                newState = JobState.ENQUEUED,
                lockedBy = null,
                lockedUntilMillis = null,
            ),
        )

        val result = scheduler.cancel(rootId, by = ACTOR)
        assertEquals(CancelResult.CANCELLED, result)

        val child = jobs.findById(childId)!!
        assertEquals(
            JobState.ENQUEUED, child.state,
            "child already past AWAITING_DEPS must stay ENQUEUED — cascade only touches AWAITING_DEPS rows",
        )
        assertNull(child.cancelRequestedBy, "untouched child must NOT have cancel_requested_by stamped")
    }

    // Cycle-defence note: the public API can't construct a cycle — `enqueueAfter` always
    // points children at already-inserted parents, so back-edges are impossible. The BFS
    // carries a `visited` HashSet anyway as a cheap defence for future bulk-import paths.
}
