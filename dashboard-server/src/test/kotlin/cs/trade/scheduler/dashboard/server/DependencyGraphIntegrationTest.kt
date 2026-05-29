@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package cs.trade.scheduler.dashboard.server

import cs.trade.scheduler.core.backend.Scheduler
import cs.trade.scheduler.core.backend.handler.Job
import cs.trade.scheduler.dashboard.server.domain.usecases.GetJobDetailUseCase
import io.ktor.server.application.install
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.koin.ktor.plugin.Koin
import kotlin.uuid.Uuid

/**
 * Transitive dependency-graph assembly in [GetJobDetailUseCase] (DESIGN.md 9.6). The use
 * case BFS-walks the whole DAG component around the focal job in BOTH directions; these
 * tests build real DAGs through `Scheduler.chain` / `enqueueAfter` (which write the
 * `job_dependency` edges) and assert the collected nodes + directed edges.
 *
 * Unit-level CAS / edge-insert semantics live in `:storage-postgres`; here we prove the
 * graph the dashboard renders is correct. No HTTP — we resolve the use case straight from
 * the Koin graph the Ktor plugin installs (same `EXTERNAL_PG_URL` bypass as the sibling
 * dashboard tests). Isolation is by graph connectivity: other test classes' jobs aren't
 * reachable from our focal id, so they never appear in the walk.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DependencyGraphIntegrationTest {

    @Serializable
    data class GraphTestPayload(val n: Long) : Job

    private lateinit var storage: DashboardTestSupport.Storage

    @BeforeAll
    fun setUp() {
        storage = DashboardTestSupport.startStorage()
    }

    @AfterAll
    fun tearDown() {
        DashboardTestSupport.stopKoinSafe()
        storage.shutdown()
    }

    // Boots a Koin-only app (no routing/HTTP needed) so GlobalContext is populated, then
    // runs the assertion body. startApplication() forces the config block to run before
    // the body so resolve() doesn't race the plugin install.
    private fun graphApp(block: suspend () -> Unit) = testApplication {
        application {
            install(Koin) {
                modules(*DashboardTestSupport.dashboardModules(storage.dataSource, nodeId = "test-graph"))
            }
        }
        startApplication()
        block()
    }

    @Test
    fun `chain collects both ancestors and descendants of the focal job`() = runBlocking {
        graphApp {
            val scheduler: Scheduler = DashboardTestSupport.resolve()
            val getDetail: GetJobDetailUseCase = DashboardTestSupport.resolve()

            // a -> b -> c (each step PROPAGATE_FAILURE). Focal = b, so the walk must reach
            // UP to a (ancestor) and DOWN to c (descendant).
            val ids = scheduler.chain(GraphTestPayload(1), GraphTestPayload(2), GraphTestPayload(3))
            assertEquals(3, ids.size)
            val (a, b, c) = Triple(ids[0], ids[1], ids[2])

            val graph = getDetail(b).getOrThrow()!!.graph

            assertEquals(setOf(a, b, c), graph.nodes.map { it.id }.toSet(), "whole chain component")
            assertEquals(
                setOf(a to b, b to c),
                graph.edges.map { it.parentId to it.childId }.toSet(),
                "directed edges a->b, b->c",
            )
            assertFalse(graph.truncated)
        }
    }

    @Test
    fun `diamond collects every node once and keeps both converging edges`() = runBlocking {
        graphApp {
            val scheduler: Scheduler = DashboardTestSupport.resolve()
            val getDetail: GetJobDetailUseCase = DashboardTestSupport.resolve()

            //   a          a is the focal root; d is reachable via TWO paths (b and c).
            //  / \         The BFS must list d exactly once as a node yet keep both
            // b   c        converging edges b->d and c->d.
            //  \ /
            //   d
            val a = scheduler.enqueue(GraphTestPayload(10))
            val b = scheduler.enqueueAfter(GraphTestPayload(11), waitFor = listOf(a))
            val c = scheduler.enqueueAfter(GraphTestPayload(12), waitFor = listOf(a))
            val d = scheduler.enqueueAfter(GraphTestPayload(13), waitFor = listOf(b, c))

            val graph = getDetail(a).getOrThrow()!!.graph

            assertEquals(setOf(a, b, c, d), graph.nodes.map { it.id }.toSet())
            assertEquals(4, graph.nodes.size, "d appears once despite two inbound paths")
            assertEquals(
                setOf(a to b, a to c, b to d, c to d),
                graph.edges.map { it.parentId to it.childId }.toSet(),
            )
            assertFalse(graph.truncated)
        }
    }

    @Test
    fun `standalone job yields a single-node, zero-edge graph`() = runBlocking {
        graphApp {
            val scheduler: Scheduler = DashboardTestSupport.resolve()
            val getDetail: GetJobDetailUseCase = DashboardTestSupport.resolve()

            val solo: Uuid = scheduler.enqueue(GraphTestPayload(20))
            val graph = getDetail(solo).getOrThrow()!!.graph

            // The focal id always seeds the node set, so a depless job is one node, no edges
            // — which is exactly what the UI's `edges.isNotEmpty()` guard uses to hide the
            // graph section.
            assertEquals(listOf(solo), graph.nodes.map { it.id })
            assertTrue(graph.edges.isEmpty())
            assertFalse(graph.truncated)
        }
    }
}
