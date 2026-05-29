@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package cs.trade.scheduler.dashboard.server.domain.usecases

import cs.trade.scheduler.core.backend.usecases.BaseUseCase
import cs.trade.scheduler.core.backend.usecases.runCatchingWithLogging
import cs.trade.scheduler.storage.postgres.domain.models.Job
import cs.trade.scheduler.storage.postgres.domain.models.JobDependency
import cs.trade.scheduler.storage.postgres.domain.models.JobEventRow
import cs.trade.scheduler.storage.postgres.domain.repositories.JobDependencyRepository
import cs.trade.scheduler.storage.postgres.domain.repositories.JobEventRepository
import cs.trade.scheduler.storage.postgres.domain.repositories.JobRepository
import org.koin.core.annotation.Single
import kotlin.uuid.Uuid

// Loads everything the JobDetail screen needs in one UseCase: the row itself, its event
// timeline, and the transitive dependency-graph component it belongs to (DESIGN.md 9.6).
//
// The graph is a breadth-first walk in BOTH directions from the focal job over
// JobDependencyRepository edges, hydrating each discovered id via findById. It's bounded by
// [MAX_GRAPH_NODES]; once the cap is hit we stop expanding and set [JobGraphResult.truncated]
// so the UI says the picture is partial rather than implying it's the whole DAG.
//
// No transaction wraps the reads — each piece may be one tick stale relative to the others,
// which the dashboard tolerates (it polls / receives WS invalidations and refetches).
//
// N+1 round trips (1 edge SELECT per visited node + 1 findById per node). Acceptable for the
// typical DAGs we expect (chains of 2-5, fan-in/out of a handful). If wide DAGs become common,
// a recursive-CTE batch fetch lands in the repository.
@Single
public class GetJobDetailUseCase(
    private val jobs: JobRepository,
    private val events: JobEventRepository,
    private val deps: JobDependencyRepository,
) : BaseUseCase() {

    public suspend operator fun invoke(jobId: Uuid): Result<JobWithEvents?> = runCatchingWithLogging {
        val job = jobs.findById(jobId) ?: return@runCatchingWithLogging null
        val eventsList = events.findByJobId(jobId)
        val graph = buildGraph(jobId)
        JobWithEvents(job = job, events = eventsList, graph = graph)
    }

    /**
     * BFS the connected DAG component around [focalId], collecting node rows and directed
     * edges. The same logical edge is discovered twice (once from each endpoint) — the
     * LinkedHashSet de-dupes on [JobDependency] value equality. Edges whose far endpoint was
     * dropped at the node cap are filtered out so no dangling edge reaches the UI. The focal
     * id seeds the node set, so a standalone job yields a single-node, zero-edge graph.
     */
    private suspend fun buildGraph(focalId: Uuid): JobGraphResult {
        val nodeIds = LinkedHashSet<Uuid>().apply { add(focalId) }
        val edges = LinkedHashSet<JobDependency>()
        val queue = ArrayDeque<Uuid>().apply { add(focalId) }
        val visited = HashSet<Uuid>()
        var truncated = false

        while (queue.isNotEmpty()) {
            val cur = queue.removeFirst()
            if (!visited.add(cur)) continue

            val parentEdges = deps.findParentsOfChild(cur)   // parent -> cur
            val childEdges = deps.findChildrenOfParent(cur)  // cur -> child
            edges.addAll(parentEdges)
            edges.addAll(childEdges)

            val neighbours = parentEdges.map { it.parentId } + childEdges.map { it.childId }
            for (neighbour in neighbours) {
                if (neighbour in nodeIds) continue
                if (nodeIds.size >= MAX_GRAPH_NODES) {
                    truncated = true
                } else {
                    nodeIds.add(neighbour)
                    queue.add(neighbour)
                }
            }
        }

        val nodes = nodeIds.mapNotNull { jobs.findById(it) }
        val keptEdges = edges.filter { it.parentId in nodeIds && it.childId in nodeIds }
        return JobGraphResult(nodes = nodes, edges = keptEdges, truncated = truncated)
    }

    public data class JobWithEvents(
        val job: Job,
        val events: List<JobEventRow>,
        val graph: JobGraphResult,
    )

    /** Domain-side graph payload; [cs.trade.scheduler.dashboard.server.api.mappers.JobApiMapper] converts it to the wire `JobGraph` DTO. */
    public data class JobGraphResult(
        val nodes: List<Job>,
        val edges: List<JobDependency>,
        val truncated: Boolean,
    )

    private companion object {
        // Cap on transitive graph size. Beyond this the component is truncated — keeps a
        // pathological fan-out (thousands of children) from N+1-ing the DB and flooding the UI.
        const val MAX_GRAPH_NODES = 100
    }
}
