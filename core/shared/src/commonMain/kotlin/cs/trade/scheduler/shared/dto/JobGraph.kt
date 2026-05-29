package cs.trade.scheduler.shared.dto

import cs.trade.scheduler.shared.OnFailure
import kotlinx.serialization.Serializable

/**
 * Dependency-graph adjacency for the JobDetail screen (DESIGN.md 9.6). Unlike the old flat
 * parents/children lists, this is the whole transitive DAG component the focal job belongs
 * to — every reachable ancestor and descendant — so the dashboard can draw a real node-link
 * diagram instead of two one-hop lists.
 *
 * [nodes] always contains the focal job itself. [edges] are directed parent → child. The
 * server walks the component breadth-first in both directions and caps it at a node limit;
 * [truncated] is true when that cap was hit (some far ancestors/descendants omitted), so the
 * UI can say so rather than imply the graph is complete.
 */
@Serializable
public data class JobGraph(
    val nodes: List<JobView>,
    val edges: List<JobGraphEdge>,
    val truncated: Boolean = false,
)

/**
 * One directed DAG edge: [parentId] must reach a terminal state before [childId] runs.
 * [onFailure] is what happens to the child if the parent ends FAILED/CANCELLED — the UI
 * colours the edge by it. Ids are canonical UUID strings (KMP-safe, like [JobView.id]).
 */
@Serializable
public data class JobGraphEdge(
    val parentId: String,
    val childId: String,
    val onFailure: OnFailure,
)
