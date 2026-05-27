package cs.trade.scheduler.shared.dto

import kotlinx.serialization.Serializable

/**
 * Full job view including events timeline and dependency graph adjacency.
 * Returned by `GET /api/jobs/{id}`.
 */
@Serializable
public data class JobDetail(
    val job: JobView,
    val payloadJson: String,
    val events: List<JobEventDto>,
    val parents: List<JobView>,
    val children: List<JobView>,
)
