package cs.trade.scheduler.dashboard.web.data.repositories

import cs.trade.scheduler.core.frontend.api.ApiClient
import cs.trade.scheduler.dashboard.web.domain.repositories.JobsRepository
import cs.trade.scheduler.shared.CancelResult
import cs.trade.scheduler.shared.DeleteResult
import cs.trade.scheduler.shared.JobState
import cs.trade.scheduler.shared.RerouteResult
import cs.trade.scheduler.shared.RetryMode
import cs.trade.scheduler.shared.RetryResult
import cs.trade.scheduler.shared.dto.BulkActionResponse
import cs.trade.scheduler.shared.dto.BulkIdsRequest
import cs.trade.scheduler.shared.dto.CancelJobResponse
import cs.trade.scheduler.shared.dto.DeleteJobResponse
import cs.trade.scheduler.shared.dto.GetJobDetailResponse
import cs.trade.scheduler.shared.dto.JobDetail
import cs.trade.scheduler.shared.dto.ListJobsResponse
import cs.trade.scheduler.shared.dto.RerouteJobResponse
import cs.trade.scheduler.shared.dto.RerunJobResponse
import cs.trade.scheduler.shared.dto.RetryJobResponse
import io.ktor.client.call.body
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.isSuccess

/**
 * Ktor-Js calls to the dashboard server. Same-origin (`/api/...`) — webpack devServer
 * proxies during local dev, identical paths in prod.
 *
 * No retries / backoff in MVP — the UI surfaces failures via the screen's `error` state.
 */
public class JobsRepositoryImpl : JobsRepository {

    override suspend fun list(
        states: Set<JobState>,
        queue: String?,
        payloadType: String?,
        page: Int,
        size: Int,
        attemptsExhausted: Boolean?,
    ): ListJobsResponse {
        val resp = ApiClient.http.get(JOBS_PATH) {
            states.forEach { parameter("state", it.name) }
            queue?.let { parameter("queue", it) }
            payloadType?.let { parameter("payloadType", it) }
            parameter("page", page)
            parameter("size", size)
            attemptsExhausted?.let { parameter("attemptsExhausted", it.toString()) }
        }
        return resp.expectSuccess().body()
    }

    override suspend fun detail(jobId: String): JobDetail? {
        val resp = ApiClient.http.get("$JOBS_PATH/$jobId")
        return when (resp.status) {
            HttpStatusCode.NotFound -> null
            else -> resp.expectSuccess().body<GetJobDetailResponse>().detail
        }
    }

    override suspend fun cancel(jobId: String, by: String?): CancelResult {
        val resp = ApiClient.http.post("$JOBS_PATH/$jobId/cancel") {
            by?.let { parameter("by", it) }
        }
        // Server returns CancelJobResponse with the result, even on 404/409 — the body has
        // the verdict, the HTTP code is just for client-style error handling.
        return when (resp.status) {
            HttpStatusCode.NotFound -> CancelResult.NOT_FOUND
            HttpStatusCode.Conflict -> resp.body<CancelJobResponse>().result
            else -> resp.expectSuccess().body<CancelJobResponse>().result
        }
    }

    override suspend fun retry(jobId: String, by: String?, mode: RetryMode): RetryResult {
        val resp = ApiClient.http.post("$JOBS_PATH/$jobId/retry") {
            by?.let { parameter("by", it) }
            // Default fresh-budget retry sends no mode param (server defaults to it);
            // "Retry +1" opts into the single-extra-attempt path (DESIGN.md 9.5).
            if (mode == RetryMode.ONCE) parameter("mode", "once")
        }
        // Mirror cancel(): server packs the verdict into the body for all expected
        // statuses (200 / 404 / 409). We unpack uniformly and let the screen layer
        // decide what to surface.
        return when (resp.status) {
            HttpStatusCode.NotFound -> RetryResult.NOT_FOUND
            HttpStatusCode.Conflict -> resp.body<RetryJobResponse>().result
            else -> resp.expectSuccess().body<RetryJobResponse>().result
        }
    }

    override suspend fun delete(jobId: String, by: String?): DeleteResult {
        val resp = ApiClient.http.delete("$JOBS_PATH/$jobId") {
            by?.let { parameter("by", it) }
        }
        return when (resp.status) {
            HttpStatusCode.NotFound -> DeleteResult.NOT_FOUND
            HttpStatusCode.Conflict -> resp.body<DeleteJobResponse>().result
            else -> resp.expectSuccess().body<DeleteJobResponse>().result
        }
    }

    override suspend fun reroute(
        jobId: String,
        targetNode: String?,
        targetTag: String?,
        by: String?,
    ): RerouteResult {
        val resp = ApiClient.http.post("$JOBS_PATH/$jobId/reroute") {
            targetNode?.takeIf { it.isNotBlank() }?.let { parameter("targetNode", it) }
            targetTag?.takeIf { it.isNotBlank() }?.let { parameter("targetTag", it) }
            by?.let { parameter("by", it) }
        }
        return when (resp.status) {
            HttpStatusCode.NotFound -> RerouteResult.NOT_FOUND
            HttpStatusCode.Conflict -> resp.body<RerouteJobResponse>().result
            else -> resp.expectSuccess().body<RerouteJobResponse>().result
        }
    }

    override suspend fun rerun(jobId: String): String? {
        val resp = ApiClient.http.post("$JOBS_PATH/$jobId/rerun")
        return when (resp.status) {
            HttpStatusCode.NotFound -> null
            else -> resp.expectSuccess().body<RerunJobResponse>().jobId
        }
    }

    override suspend fun bulkRetry(ids: List<String>, by: String?): BulkActionResponse =
        bulk("$JOBS_PATH/bulk-retry", ids, by)

    override suspend fun bulkCancel(ids: List<String>, by: String?): BulkActionResponse =
        bulk("$JOBS_PATH/bulk-cancel", ids, by)

    override suspend fun bulkDelete(ids: List<String>, by: String?): BulkActionResponse =
        bulk("$JOBS_PATH/bulk-delete", ids, by)

    private suspend fun bulk(path: String, ids: List<String>, by: String?): BulkActionResponse {
        val resp = ApiClient.http.post(path) {
            by?.let { parameter("by", it) }
            contentType(ContentType.Application.Json)
            setBody(BulkIdsRequest(ids))
        }
        return resp.expectSuccess().body()
    }

    private fun HttpResponse.expectSuccess(): HttpResponse = also {
        require(status.isSuccess()) { "HTTP ${status.value} ${status.description}" }
    }

    private companion object {
        const val JOBS_PATH = "/api/jobs"
    }
}
