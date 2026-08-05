package cs.trade.scheduler.dashboard.web.domain.repositories

import cs.trade.scheduler.shared.CancelResult
import cs.trade.scheduler.shared.DeleteResult
import cs.trade.scheduler.shared.JobSortField
import cs.trade.scheduler.shared.JobState
import cs.trade.scheduler.shared.RerouteResult
import cs.trade.scheduler.shared.RetryMode
import cs.trade.scheduler.shared.RetryResult
import cs.trade.scheduler.shared.dto.BulkActionResponse
import cs.trade.scheduler.shared.dto.JobDetail
import cs.trade.scheduler.shared.dto.ListJobsResponse

// Frontend-side contract for the /api/jobs/* REST surface. Impl in
// :dashboard-web/data/repositories/ uses cs.trade.scheduler.core.frontend.api.ApiClient.
//
// One repository per smysl group (per architecture.md). Other dashboard screens
// (recurring, workers, stats) get their own *Repository files when they land.
//
// Server-push events live on `data.connection.EventStream` (one shared WebSocket
// per tab), not here — repositories stay REST-shaped.
public interface JobsRepository {

    public suspend fun list(
        states: Set<JobState> = emptySet(),
        queue: String? = null,
        payloadType: String? = null,
        page: Int = 0,
        size: Int = 50,
        attemptsExhausted: Boolean? = null,
        // "Upcoming" window: future-dated jobs scheduled within the next N minutes, soonest-first.
        scheduledWithinMinutes: Int? = null,
        // Sort column + direction; null = server default (updated_at DESC).
        sortBy: JobSortField? = null,
        sortAscending: Boolean = false,
    ): ListJobsResponse

    public suspend fun detail(jobId: String): JobDetail?

    public suspend fun cancel(jobId: String, by: String? = null): CancelResult

    public suspend fun retry(
        jobId: String,
        by: String? = null,
        mode: RetryMode = RetryMode.FRESH_BUDGET,
    ): RetryResult

    public suspend fun delete(jobId: String, by: String? = null): DeleteResult

    /** Clone this job into a fresh ENQUEUED one. Returns the new job's id, or null if the source is gone (404). */
    public suspend fun rerun(jobId: String): String?

    public suspend fun reroute(
        jobId: String,
        targetNode: String?,
        targetTag: String?,
        by: String? = null,
    ): RerouteResult

    public suspend fun bulkRetry(ids: List<String>, by: String? = null): BulkActionResponse
    public suspend fun bulkCancel(ids: List<String>, by: String? = null): BulkActionResponse
    public suspend fun bulkDelete(ids: List<String>, by: String? = null): BulkActionResponse
}
