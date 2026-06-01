package cs.trade.scheduler.shared.dto

import cs.trade.scheduler.shared.CancelResult
import cs.trade.scheduler.shared.DeleteResult
import cs.trade.scheduler.shared.RerouteResult
import cs.trade.scheduler.shared.RetryResult
import kotlinx.serialization.Serializable

// Wire DTOs for /api/jobs/*. Shared between :dashboard-server (server-side) and
// :dashboard-web / :core:frontend (wasmJs client).

@Serializable
public data class ListJobsResponse(
    val items: List<JobView>,
    val total: Long,
    val page: Int,
    val size: Int,
)

@Serializable
public data class GetJobDetailResponse(
    val detail: JobDetail,
)

@Serializable
public data class CancelJobResponse(
    val jobId: String,
    val result: CancelResult,
)

@Serializable
public data class RetryJobResponse(
    val jobId: String,
    val result: RetryResult,
)

@Serializable
public data class DeleteJobResponse(
    val jobId: String,
    val result: DeleteResult,
)

@Serializable
public data class RerouteJobResponse(
    val jobId: String,
    val result: RerouteResult,
)

// Re-run: the source job that was cloned + the id of the fresh copy that was enqueued.
@Serializable
public data class RerunJobResponse(
    val sourceJobId: String,
    val jobId: String,
)
