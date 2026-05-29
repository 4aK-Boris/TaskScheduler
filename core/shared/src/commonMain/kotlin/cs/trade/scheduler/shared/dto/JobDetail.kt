package cs.trade.scheduler.shared.dto

import kotlinx.serialization.Serializable

/**
 * Full job view including events timeline and dependency graph adjacency.
 * Returned by `GET /api/jobs/{id}`.
 *
 * [graph] is the transitive DAG component the [job] belongs to (focal job + all reachable
 * ancestors/descendants), replacing the earlier one-hop parents/children lists so the
 * dashboard can render a node-link diagram. A standalone job (no edges) still gets a
 * single-node graph.
 */
@Serializable
public data class JobDetail(
    val job: JobView,
    val payloadJson: String,
    val events: List<JobEventDto>,
    val graph: JobGraph,
)
