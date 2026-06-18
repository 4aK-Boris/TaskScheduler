@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package cs.trade.scheduler.engine.worker

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import cs.trade.scheduler.core.backend.EnqueueOptions
import cs.trade.scheduler.core.backend.SchedulerCoreConfig
import cs.trade.scheduler.core.backend.events.EventBus
import cs.trade.scheduler.core.backend.handler.Job
import cs.trade.scheduler.engine.worker.domain.usecases.FinalizeJobUseCase
import cs.trade.scheduler.engine.worker.domain.usecases.PropagateRollupProgressUseCase
import cs.trade.scheduler.shared.JobState
import cs.trade.scheduler.shared.OnFailure
import cs.trade.scheduler.storage.postgres.infrastructure.PostgresStorageProvider
import cs.trade.scheduler.storage.postgres.infrastructure.repositories.JobDependencyRepositoryImpl
import cs.trade.scheduler.storage.postgres.infrastructure.repositories.JobRepositoryImpl
import cs.trade.scheduler.storage.postgres.infrastructure.repositories.JobRollupRepositoryImpl
import cs.trade.scheduler.storage.postgres.infrastructure.repositories.OutboxRepositoryImpl
import cs.trade.scheduler.storage.postgres.infrastructure.repositories.RecurringJobRepositoryImpl
import cs.trade.scheduler.storage.postgres.infrastructure.scheduler.DefaultScheduler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import org.flywaydb.core.Flyway
import org.jetbrains.exposed.v1.jdbc.Database
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.testcontainers.containers.PostgreSQLContainer

/**
 * DAG resolution covered at the repository + FinalizeJobUseCase level (no Rabbit, no
 * workers). Each test verifies one slice of the DAG behaviour described in DESIGN.md 7.4 /
 * 8.3 / 8.4.
 *
 * **PG provisioning.** Honours `EXTERNAL_PG_URL` for the shared scheduler-test-pg
 * setup; falls back to Testcontainers when absent. Manual lifecycle so the env
 * override can short-circuit Docker.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DagIntegrationTest {

    @Serializable data class StepA(val n: Long) : Job
    @Serializable data class StepB(val n: Long) : Job
    @Serializable data class StepC(val n: Long) : Job

    private companion object {
        private val externalUrl: String? = System.getenv("EXTERNAL_PG_URL")?.takeIf { it.isNotBlank() }
    }

    private lateinit var dataSource: HikariDataSource
    private lateinit var database: Database
    private lateinit var jobs: JobRepositoryImpl
    private lateinit var outbox: OutboxRepositoryImpl
    private lateinit var deps: JobDependencyRepositoryImpl
    private lateinit var rollups: JobRollupRepositoryImpl
    private lateinit var storage: PostgresStorageProvider
    private lateinit var scheduler: DefaultScheduler
    private lateinit var finalize: FinalizeJobUseCase
    private lateinit var propagateRollup: PropagateRollupProgressUseCase
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
        Flyway.configure().dataSource(dataSource).locations("classpath:scheduler/migration").load().migrate()

        database = Database.connect(dataSource)
        jobs = JobRepositoryImpl(database)
        outbox = OutboxRepositoryImpl(database)
        deps = JobDependencyRepositoryImpl(database)
        val recurring = RecurringJobRepositoryImpl(database)
        val jobEventsRepo = cs.trade.scheduler.storage.postgres.infrastructure.repositories.JobEventRepositoryImpl(database)
        val workersRepo = cs.trade.scheduler.storage.postgres.infrastructure.repositories.WorkerRepositoryImpl(database)
        rollups = JobRollupRepositoryImpl(database)
        storage = PostgresStorageProvider(
            jobs = jobs,
            outbox = outbox,
            jobDependencies = deps,
            recurringJobs = recurring,
            jobEvents = jobEventsRepo,
            workers = workersRepo,
            idempotencyLog = cs.trade.scheduler.storage.postgres.infrastructure.repositories.IdempotencyLogRepositoryImpl(database),
            jobRollups = rollups,
            jobTypePauses = cs.trade.scheduler.storage.postgres.infrastructure.repositories.JobTypePauseRepositoryImpl(database),
        )
        scheduler = DefaultScheduler(
            storage = storage,
            database = database,
            config = SchedulerCoreConfig().apply { nodeId = "test-dag" },
        )
        propagateRollup = PropagateRollupProgressUseCase(jobs, rollups, EventBus.NoOp)
        finalize = FinalizeJobUseCase(database, jobs, outbox, deps, propagateRollup)
    }

    @BeforeEach
    fun cleanTables() {
        // Shared `scheduler-test-pg` accumulates unpublished outbox rows across runs, and
        // the `findUnpublished(limit = …)` assertions below are only load-bearing on a
        // clean slate (same pattern as SafetyNetIntegrationTest). CASCADE clears
        // job_dependency / job_event along with job.
        runCatching {
            dataSource.connection.use { conn ->
                conn.createStatement().use { it.execute("TRUNCATE job, outbox RESTART IDENTITY CASCADE") }
            }
        }
    }

    @AfterAll
    fun tearDown() {
        runCatching { dataSource.close() }
        runCatching { postgres?.stop() }
    }

    @Test
    fun `priority inheritance — child takes max of parent priorities when flag set`() = runBlocking {
        // Two parents with different priorities (DESIGN.md 19.7 Phase 3).
        val lowPrio = scheduler.enqueue(StepA(1), EnqueueOptions(priority = 2))
        val highPrio = scheduler.enqueue(StepA(2), EnqueueOptions(priority = 8))

        val childId = scheduler.enqueueAfter(
            job = StepB(1),
            waitFor = listOf(lowPrio, highPrio),
            options = EnqueueOptions(inheritPriorityFromParents = true),
        )

        val child = jobs.findById(childId)!!
        assertEquals(
            8, child.priority.value,
            "child must inherit max(parents.priority) — got ${child.priority.value} from parents 2, 8",
        )
    }

    @Test
    fun `priority inheritance — explicit priority wins over flag`() = runBlocking {
        val highPrioParent = scheduler.enqueue(StepA(1), EnqueueOptions(priority = 9))

        val childId = scheduler.enqueueAfter(
            job = StepB(1),
            waitFor = listOf(highPrioParent),
            // BOTH explicit priority AND inherit flag — explicit wins (override chain
            // priority 1 from DESIGN.md 19.3 extended).
            options = EnqueueOptions(priority = 3, inheritPriorityFromParents = true),
        )

        val child = jobs.findById(childId)!!
        assertEquals(
            3, child.priority.value,
            "explicit priority in EnqueueOptions must override the inheritance flag",
        )
    }

    @Test
    fun `priority inheritance — flag off keeps default zero even with high-priority parent`() = runBlocking {
        val highPrioParent = scheduler.enqueue(StepA(1), EnqueueOptions(priority = 7))

        val childId = scheduler.enqueueAfter(
            job = StepB(1),
            waitFor = listOf(highPrioParent),
            options = EnqueueOptions(),    // no priority, no flag — defaults
        )

        val child = jobs.findById(childId)!!
        assertEquals(
            0, child.priority.value,
            "without the flag, child priority must default to 0 regardless of parent",
        )
    }

    @Test
    fun `chain with priority applies it to every step`() = runBlocking {
        // The DESIGN.md 19.7 convenience helper — one priority for the whole pipeline.
        val ids = scheduler.chain(StepA(50), StepB(50), StepC(50), priority = 7)
        assertEquals(3, ids.size)
        ids.forEach { id ->
            assertEquals(7, jobs.findById(id)!!.priority.value, "every chain step must carry the chain priority")
        }
    }

    @Test
    fun `chain without priority leaves every step at the default`() = runBlocking {
        // Guards the backward-compatible path: null must fall through to the per-step
        // default (0 here), NOT pin an explicit 0 that would shadow a handler/queue default.
        val ids = scheduler.chain(StepA(51), StepB(51), priority = null)
        ids.forEach { id ->
            assertEquals(0, jobs.findById(id)!!.priority.value, "no chain priority → per-step default")
        }
    }

    @Test
    fun `enqueueAfter rejects fan-in above maxDagFanIn`() = runBlocking {
        // A scheduler with a deliberately tiny fan-in cap, sharing the same storage (22.10).
        val limited = DefaultScheduler(
            storage = storage,
            database = database,
            config = SchedulerCoreConfig().apply { nodeId = "test-dag-fanin"; maxDagFanIn = 2 },
        )
        val p1 = limited.enqueue(StepA(60))
        val p2 = limited.enqueue(StepA(61))
        val p3 = limited.enqueue(StepA(62))

        // Distinct fan-in == cap → allowed, child row created.
        val okChild = limited.enqueueAfter(StepB(60), waitFor = listOf(p1, p2))
        assertNotNull(jobs.findById(okChild), "fan-in at the cap must be allowed")

        // Over the cap → fail-fast IllegalArgumentException, nothing written.
        val ex = assertThrows(IllegalArgumentException::class.java) {
            runBlocking { limited.enqueueAfter(StepB(61), waitFor = listOf(p1, p2, p3)) }
        }
        assertTrue(ex.message!!.contains("maxDagFanIn"), ex.message)
    }

    @Test
    fun `chain rejects length above maxChainLength`() = runBlocking {
        val limited = DefaultScheduler(
            storage = storage,
            database = database,
            config = SchedulerCoreConfig().apply { nodeId = "test-dag-chainlen"; maxChainLength = 2 },
        )
        // Length == cap → allowed.
        assertEquals(2, limited.chain(StepA(70), StepB(70)).size)
        // Over the cap → fail-fast, nothing enqueued.
        val ex = assertThrows(IllegalArgumentException::class.java) {
            runBlocking { limited.chain(StepA(71), StepB(71), StepC(71)) }
        }
        assertTrue(ex.message!!.contains("maxChainLength"), ex.message)
    }

    @Test
    fun `enqueueAfter creates child in AWAITING_DEPS with pending_deps=1`() = runBlocking {
        val parentId = scheduler.enqueue(StepA(1))
        val childId = scheduler.enqueueAfter(
            job = StepB(1),
            waitFor = listOf(parentId),
            options = EnqueueOptions(),
        )

        val child = jobs.findById(childId)!!
        assertEquals(JobState.AWAITING_DEPS, child.state)
        assertEquals(1, child.pendingDeps)

        // Child has NO outbox row yet.
        assertTrue(outbox.findUnpublished(limit = 100).none { it.jobId == childId })

        // Dep edge exists.
        val edges = deps.findChildrenOfParent(parentId)
        assertEquals(1, edges.size)
        assertEquals(childId, edges[0].childId)
        assertEquals(OnFailure.PROPAGATE_FAILURE, edges[0].onFailure)
    }

    @Test
    fun `finalize parent SUCCEEDED promotes child to ENQUEUED with outbox`() = runBlocking {
        val parentId = scheduler.enqueue(StepA(10))
        val childId = scheduler.enqueueAfter(StepB(10), listOf(parentId), EnqueueOptions())
        val parentVersion = jobs.findById(parentId)!!.version

        val ok = finalize(parentId, parentVersion, JobState.SUCCEEDED).getOrThrow()
        assertTrue(ok)

        val child = jobs.findById(childId)!!
        assertEquals(JobState.ENQUEUED, child.state, "Promoted from AWAITING_DEPS → ENQUEUED")
        assertEquals(0, child.pendingDeps)

        val row = outbox.findUnpublished(limit = 100).single { it.jobId == childId }
        assertEquals(0, row.delayMs)
    }

    @Test
    fun `fan-in waits for ALL parents and promotes after the last one`() = runBlocking {
        val pA = scheduler.enqueue(StepA(20))
        val pB = scheduler.enqueue(StepA(21))
        val childId = scheduler.enqueueAfter(
            job = StepC(20),
            waitFor = listOf(pA, pB),
            options = EnqueueOptions(),
        )
        assertEquals(2, jobs.findById(childId)!!.pendingDeps)

        // Finalize first parent — child stays AWAITING_DEPS, counter ticks to 1.
        finalize(pA, jobs.findById(pA)!!.version, JobState.SUCCEEDED).getOrThrow()
        var child = jobs.findById(childId)!!
        assertEquals(JobState.AWAITING_DEPS, child.state)
        assertEquals(1, child.pendingDeps)
        assertTrue(outbox.findUnpublished(limit = 100).none { it.jobId == childId })

        // Finalize second parent — child promotes.
        finalize(pB, jobs.findById(pB)!!.version, JobState.SUCCEEDED).getOrThrow()
        child = jobs.findById(childId)!!
        assertEquals(JobState.ENQUEUED, child.state)
        assertEquals(0, child.pendingDeps)
        assertEquals(1, outbox.findUnpublished(limit = 100).count { it.jobId == childId })
    }

    @Test
    fun `concurrent fan-in finalization promotes the child exactly once — no lost decrement`() = runBlocking {
        // Regression for the prod incident (2026-06-12): 8 cache-warmup parents finishing
        // within milliseconds of each other lost one pending_deps decrement, leaving the
        // barrier child in AWAITING_DEPS forever (pending_deps stuck at 1 of 8). Several
        // rounds because the race is probabilistic on the unfixed code.
        val fanIn = 8
        repeat(10) { round ->
            val parents = (1..fanIn).map { n -> scheduler.enqueue(StepA(round * 100L + n)) }
            val childId = scheduler.enqueueAfter(
                job = StepB(round.toLong()),
                waitFor = parents,
                options = EnqueueOptions(onParentFailure = OnFailure.IGNORE),
            )
            // Versions BEFORE the concurrent phase — finalize CAS-es on them.
            val versions = parents.associateWith { jobs.findById(it)!!.version }

            coroutineScope {
                parents.map { parentId ->
                    async(Dispatchers.Default) {
                        finalize(parentId, versions.getValue(parentId), JobState.SUCCEEDED).getOrThrow()
                    }
                }.awaitAll()
            }

            val child = jobs.findById(childId)!!
            assertEquals(
                0, child.pendingDeps,
                "round $round: all $fanIn parents SUCCEEDED but pending_deps=${child.pendingDeps} — lost decrement(s)",
            )
            assertEquals(
                JobState.ENQUEUED, child.state,
                "round $round: child must promote AWAITING_DEPS → ENQUEUED after the last parent",
            )
            assertEquals(
                1, outbox.findUnpublished(limit = 1000).count { it.jobId == childId },
                "round $round: exactly one outbox row — promoted once, not zero and not twice",
            )
        }
    }

    @Test
    fun `enqueueAfter on an already-terminal parent promotes the child immediately`() = runBlocking {
        // Regression for the prod warmup-gate (2026-06-18): a parent that finished BEFORE its
        // dependency edge existed never decremented, stranding the child at pending_deps=1
        // (CachesWarmedJob stuck AWAITING_DEPS). enqueueAfter must lock the parent, see it
        // terminal, and promote the child itself.
        val parentId = scheduler.enqueue(StepA(80))
        finalize(parentId, jobs.findById(parentId)!!.version, JobState.SUCCEEDED).getOrThrow()

        // Parent is terminal BEFORE the dependency is wired.
        val childId = scheduler.enqueueAfter(
            job = StepB(80),
            waitFor = listOf(parentId),
            options = EnqueueOptions(onParentFailure = OnFailure.IGNORE),
        )

        val child = jobs.findById(childId)!!
        assertEquals(0, child.pendingDeps, "already-terminal parent must not leave a pending dep")
        assertEquals(JobState.ENQUEUED, child.state, "only parent already finished → child promotes on enqueue")
        assertEquals(1, outbox.findUnpublished(limit = 100).count { it.jobId == childId }, "promoted child needs one outbox row")
    }

    @Test
    fun `enqueueAfter counts only still-live parents when some already finished`() = runBlocking {
        val finishedParent = scheduler.enqueue(StepA(81))
        val liveParent = scheduler.enqueue(StepA(82))
        finalize(finishedParent, jobs.findById(finishedParent)!!.version, JobState.SUCCEEDED).getOrThrow()

        // One parent already SUCCEEDED, the other still live, when the barrier is wired.
        val childId = scheduler.enqueueAfter(
            job = StepC(81),
            waitFor = listOf(finishedParent, liveParent),
            options = EnqueueOptions(onParentFailure = OnFailure.IGNORE),
        )

        var child = jobs.findById(childId)!!
        assertEquals(JobState.AWAITING_DEPS, child.state, "still waits on the live parent")
        assertEquals(1, child.pendingDeps, "only the live parent is pending — the finished one was settled at enqueue")
        assertTrue(outbox.findUnpublished(limit = 100).none { it.jobId == childId })

        // Finishing the live parent promotes the child.
        finalize(liveParent, jobs.findById(liveParent)!!.version, JobState.SUCCEEDED).getOrThrow()
        child = jobs.findById(childId)!!
        assertEquals(JobState.ENQUEUED, child.state)
        assertEquals(0, child.pendingDeps)
        assertEquals(1, outbox.findUnpublished(limit = 100).count { it.jobId == childId })
    }

    @Test
    fun `enqueueAfter on an already-FAILED parent cascades terminal under PROPAGATE_FAILURE`() = runBlocking {
        val parentId = scheduler.enqueue(StepA(83))
        finalize(parentId, jobs.findById(parentId)!!.version, JobState.FAILED).getOrThrow()

        // Parent already FAILED before the edge — with PROPAGATE_FAILURE the child is born FAILED.
        val childId = scheduler.enqueueAfter(
            job = StepB(83),
            waitFor = listOf(parentId),
            options = EnqueueOptions(onParentFailure = OnFailure.PROPAGATE_FAILURE),
        )

        val child = jobs.findById(childId)!!
        assertEquals(JobState.FAILED, child.state, "already-failed parent + PROPAGATE_FAILURE → child cascaded FAILED")
        assertTrue(outbox.findUnpublished(limit = 100).none { it.jobId == childId }, "cascaded-failed child gets no outbox row")
    }

    @Test
    fun `chain builds sequential deps that propagate in order`() = runBlocking {
        val ids = scheduler.chain(StepA(30), StepB(30), StepC(30))
        assertEquals(3, ids.size)
        val (idA, idB, idC) = Triple(ids[0], ids[1], ids[2])

        assertEquals(JobState.ENQUEUED, jobs.findById(idA)!!.state, "First in chain is ENQUEUED")
        assertEquals(JobState.AWAITING_DEPS, jobs.findById(idB)!!.state)
        assertEquals(JobState.AWAITING_DEPS, jobs.findById(idC)!!.state)
        assertEquals(1, jobs.findById(idB)!!.pendingDeps)
        assertEquals(1, jobs.findById(idC)!!.pendingDeps)

        finalize(idA, jobs.findById(idA)!!.version, JobState.SUCCEEDED).getOrThrow()
        assertEquals(JobState.ENQUEUED, jobs.findById(idB)!!.state)
        assertEquals(JobState.AWAITING_DEPS, jobs.findById(idC)!!.state, "Still waits on B")

        finalize(idB, jobs.findById(idB)!!.version, JobState.SUCCEEDED).getOrThrow()
        assertEquals(JobState.ENQUEUED, jobs.findById(idC)!!.state)
    }

    @Test
    fun `PROPAGATE_FAILURE cascades FAILED state down the chain`() = runBlocking {
        val ids = scheduler.chain(StepA(40), StepB(40), StepC(40))
        val (idA, idB, idC) = Triple(ids[0], ids[1], ids[2])

        // Fail the root — both B and C should cascade to FAILED.
        finalize(idA, jobs.findById(idA)!!.version, JobState.FAILED).getOrThrow()

        assertEquals(JobState.FAILED, jobs.findById(idA)!!.state)
        assertEquals(JobState.FAILED, jobs.findById(idB)!!.state, "Cascaded from A")
        assertEquals(JobState.FAILED, jobs.findById(idC)!!.state, "Cascaded transitively from B")

        // No outbox rows for cascaded failures — they go straight to terminal.
        assertTrue(outbox.findUnpublished(limit = 100).none { it.jobId == idB || it.jobId == idC })
    }

    @Test
    fun `dependency progress derives from (initial - remaining) on each decrement and clears on promote`() = runBlocking {
        // 3 parents → 1 child. Progress should tick 0 → 1/3 → 2/3 → null (cleared on promote).
        val pA = scheduler.enqueue(StepA(60))
        val pB = scheduler.enqueue(StepA(61))
        val pC = scheduler.enqueue(StepA(62))
        val childId = scheduler.enqueueAfter(
            job = StepC(60),
            waitFor = listOf(pA, pB, pC),
            options = EnqueueOptions(),
        )

        // Brand-new child has no progress yet — never been decremented.
        assertNull(jobs.findById(childId)!!.progress, "AWAITING_DEPS child starts with progress=null")

        finalize(pA, jobs.findById(pA)!!.version, JobState.SUCCEEDED).getOrThrow()
        val afterOne = jobs.findById(childId)!!
        assertEquals(JobState.AWAITING_DEPS, afterOne.state)
        // 1 of 3 satisfied → ~0.333. Float math, compare within an epsilon.
        assertEquals(1f / 3f, afterOne.progress!!, 1e-5f)
        assertNotNull(afterOne.progressUpdatedAt)

        finalize(pB, jobs.findById(pB)!!.version, JobState.SUCCEEDED).getOrThrow()
        val afterTwo = jobs.findById(childId)!!
        assertEquals(2f / 3f, afterTwo.progress!!, 1e-5f)

        // Last parent → PROMOTED → progress reset to null so handler.updateProgress can take over.
        finalize(pC, jobs.findById(pC)!!.version, JobState.SUCCEEDED).getOrThrow()
        val afterAll = jobs.findById(childId)!!
        assertEquals(JobState.ENQUEUED, afterAll.state)
        assertNull(afterAll.progress, "On PROMOTED progress clears so handler starts from scratch")
    }

    @Test
    fun `rollup parent aggregates child progress as avg(effective)`() = runBlocking {
        // Set up: a "summary" parent watches 4 children via rollup edges. No BLOCKS edges
        // here — we're isolating rollup behaviour. Parent starts as ENQUEUED (no deps).
        val parentId = scheduler.enqueue(StepC(100))
        val c1 = scheduler.enqueue(StepA(101))
        val c2 = scheduler.enqueue(StepA(102))
        val c3 = scheduler.enqueue(StepA(103))
        val c4 = scheduler.enqueue(StepA(104))
        listOf(c1, c2, c3, c4).forEach { rollups.attach(parentId, it) }

        // Initial: all children non-terminal, no progress reported → avg = 0.
        propagateRollup(c1).getOrThrow()
        assertEquals(0f, jobs.findById(parentId)!!.progress!!, 1e-5f)

        // Drive c1 to PROCESSING + progress=0.5. Need to flip through ENQUEUED→PROCESSING
        // for setProgress's state-scope to accept it.
        jobs.transitionState(c1, jobs.findById(c1)!!.version, JobState.PROCESSING, lockedBy = "test", lockedUntilMillis = System.currentTimeMillis() + 60_000)
        jobs.setProgress(c1, 0.5f, msg = null, at = kotlin.time.Clock.System.now())
        propagateRollup(c1).getOrThrow()
        // avg(0.5, 0, 0, 0) = 0.125
        assertEquals(0.125f, jobs.findById(parentId)!!.progress!!, 1e-5f)

        // c2 finishes. terminal contributes 1.0 to the aggregate.
        finalize(c2, jobs.findById(c2)!!.version, JobState.SUCCEEDED).getOrThrow()
        // avg(0.5, 1.0, 0, 0) = 0.375
        assertEquals(0.375f, jobs.findById(parentId)!!.progress!!, 1e-5f)

        // c1 also finishes (overrides its 0.5 → 1.0 effective).
        finalize(c1, jobs.findById(c1)!!.version, JobState.SUCCEEDED).getOrThrow()
        // avg(1, 1, 0, 0) = 0.5
        assertEquals(0.5f, jobs.findById(parentId)!!.progress!!, 1e-5f)

        // c3 fails — also counts as "done" for rollup purposes.
        jobs.transitionState(c3, jobs.findById(c3)!!.version, JobState.PROCESSING, lockedBy = "test", lockedUntilMillis = System.currentTimeMillis() + 60_000)
        finalize(c3, jobs.findById(c3)!!.version, JobState.FAILED).getOrThrow()
        // avg(1, 1, 1, 0) = 0.75
        assertEquals(0.75f, jobs.findById(parentId)!!.progress!!, 1e-5f)

        // Last child done → parent shows 1.0. Parent itself never moved past ENQUEUED in
        // this test; rollup writes anyway (state-scoped to non-terminal, ENQUEUED qualifies).
        finalize(c4, jobs.findById(c4)!!.version, JobState.SUCCEEDED).getOrThrow()
        assertEquals(1.0f, jobs.findById(parentId)!!.progress!!, 1e-5f)
    }

    @Test
    fun `IGNORE treats parent failure as success for dep counting`() = runBlocking {
        val parentId = scheduler.enqueue(StepA(50))
        val childId = scheduler.enqueueAfter(
            job = StepB(50),
            waitFor = listOf(parentId),
            options = EnqueueOptions(onParentFailure = OnFailure.IGNORE),
        )

        finalize(parentId, jobs.findById(parentId)!!.version, JobState.FAILED).getOrThrow()

        val child = jobs.findById(childId)
        assertNotNull(child)
        assertEquals(JobState.ENQUEUED, child!!.state, "IGNORE on parent failure → child still promotes")
        assertEquals(1, outbox.findUnpublished(limit = 100).count { it.jobId == childId })
    }
}
