@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package cs.trade.scheduler.storage.postgres

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import cs.trade.scheduler.core.backend.Scheduler
import cs.trade.scheduler.core.backend.handler.Job
import cs.trade.scheduler.core.backend.schedulerCoreModule
import cs.trade.scheduler.shared.JobState
import cs.trade.scheduler.shared.OnFailure
import cs.trade.scheduler.storage.postgres.domain.repositories.JobDependencyRepository
import cs.trade.scheduler.storage.postgres.domain.repositories.JobRepository
import cs.trade.scheduler.storage.postgres.infrastructure.schedulerPostgresModule
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.koin.core.context.GlobalContext
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.KoinAppDeclaration
import org.testcontainers.containers.PostgreSQLContainer

/**
 * Duplicate-parent dedup in the DAG enqueue path (DESIGN.md 22.10). Cycles are
 * structurally impossible in this API, so the only real correctness risk is a child that
 * lists the same parent twice — `after(a, a)`. Without dedup the child would carry
 * `pending_deps = 2` (only one parent ever finalises → stuck forever) and the second edge
 * INSERT would violate the composite PK.
 *
 * Asserts the two-layer fix:
 *  1. `enqueueAfter` `.distinct()`s parents → `pending_deps = 1`, exactly one edge.
 *  2. `JobDependencyRepository.insert` is idempotent (insertIgnore) → a duplicate edge is
 *     a no-op, not a PK crash.
 *
 * `EXTERNAL_PG_URL` provisioning, same as the sibling storage tests.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DagDedupIntegrationTest {

    @Serializable
    data class DagParent(val tag: String) : Job

    @Serializable
    data class DagChild(val tag: String) : Job

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
            addDataSourceProperty("stringtype", "unspecified")
        })

        val app: KoinAppDeclaration = {
            modules(
                schedulerCoreModule { nodeId = "test-dag-dedup" },
                schedulerPostgresModule {
                    this.dataSource = this@DagDedupIntegrationTest.dataSource
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
    fun `enqueueAfter with duplicate parents counts one dependency`() = runBlocking {
        val koin = GlobalContext.get()
        val scheduler = koin.get<Scheduler>()
        val jobs = koin.get<JobRepository>()
        val deps = koin.get<JobDependencyRepository>()

        val parentId = scheduler.enqueue(DagParent("dup-count"))
        val childId = scheduler.enqueueAfter(DagChild("dup-count"), waitFor = listOf(parentId, parentId, parentId))

        val child = jobs.findById(childId)
        assertNotNull(child, "child row must exist")
        assertEquals(JobState.AWAITING_DEPS, child!!.state)
        assertEquals(1, child.pendingDeps, "three references to one parent must count as a single pending dep")

        val edges = deps.findParentsOfChild(childId)
        assertEquals(1, edges.size, "only one job_dependency edge expected despite duplicate parents")
        assertEquals(parentId, edges.single().parentId)
    }

    @Test
    fun `JobDependencyRepository insert is idempotent on a duplicate edge`() = runBlocking {
        val koin = GlobalContext.get()
        val scheduler = koin.get<Scheduler>()
        val deps = koin.get<JobDependencyRepository>()

        val parentId = scheduler.enqueue(DagParent("idem"))
        val childId = scheduler.enqueueAfter(DagChild("idem"), waitFor = listOf(parentId))

        // Re-inserting the same (parent, child) edge must NOT throw on the composite PK.
        deps.insert(parentId, childId, OnFailure.PROPAGATE_FAILURE)
        deps.insert(parentId, childId, OnFailure.PROPAGATE_FAILURE)

        assertEquals(1, deps.findParentsOfChild(childId).size, "duplicate inserts must collapse to one edge")
    }
}
