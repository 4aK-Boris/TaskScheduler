@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package cs.trade.scheduler.dashboard.server.api.routes

import cs.trade.scheduler.dashboard.server.api.descriptions.JobDescription
import cs.trade.scheduler.dashboard.server.api.extractors.JobExtractor
import cs.trade.scheduler.dashboard.server.api.mappers.JobApiMapper
import cs.trade.scheduler.dashboard.server.api.validations.JobValidation
import cs.trade.scheduler.dashboard.server.api.validations.asErrorResponse
import cs.trade.scheduler.dashboard.server.domain.usecases.BulkCancelJobsUseCase
import cs.trade.scheduler.dashboard.server.domain.usecases.BulkDeleteJobsUseCase
import cs.trade.scheduler.dashboard.server.domain.usecases.BulkRetryJobsUseCase
import cs.trade.scheduler.dashboard.server.domain.usecases.CancelJobUseCase
import cs.trade.scheduler.dashboard.server.domain.usecases.DeleteJobUseCase
import cs.trade.scheduler.dashboard.server.domain.usecases.GetJobDetailUseCase
import cs.trade.scheduler.dashboard.server.domain.usecases.GetJobsListUseCase
import cs.trade.scheduler.dashboard.server.domain.usecases.RerouteJobUseCase
import cs.trade.scheduler.dashboard.server.domain.usecases.RetryJobUseCase
import cs.trade.scheduler.shared.CancelResult
import cs.trade.scheduler.shared.DeleteResult
import cs.trade.scheduler.shared.RerouteResult
import cs.trade.scheduler.shared.RetryMode
import cs.trade.scheduler.shared.RetryResult
import cs.trade.scheduler.shared.dto.BulkIdsRequest
import cs.trade.scheduler.shared.dto.CancelJobResponse
import cs.trade.scheduler.shared.dto.DeleteJobResponse
import cs.trade.scheduler.shared.dto.GetJobDetailResponse
import cs.trade.scheduler.shared.dto.ListJobsResponse
import cs.trade.scheduler.shared.dto.RerouteJobResponse
import cs.trade.scheduler.shared.dto.RetryJobResponse
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import org.koin.ktor.ext.inject
import kotlin.uuid.Uuid

/**
 * /api/jobs sub-routing. One file per smysl group (jobs, recurring, workers, types) —
 * mirrors main project's `cs.trade.<module>.api.routes/XxxYyyRouting.kt` pattern.
 *
 * Each handler follows: extractor → validation → mapper.toModel → usecase.invoke →
 * mapper.toDto → respond. Validation failures short-circuit to 400 with a structured
 * `ValidationErrorResponse`; no business logic inline.
 *
 * Wired in `:standalone-runner` (or any user-app that exposes the dashboard) by calling
 * [Route.configureDashboardRouting].
 */
public fun Route.configureJobsRouting() {
    val extractor by inject<JobExtractor>()
    val validation by inject<JobValidation>()
    val mapper by inject<JobApiMapper>()
    val getDetail by inject<GetJobDetailUseCase>()
    val getList by inject<GetJobsListUseCase>()
    val cancelJob by inject<CancelJobUseCase>()
    val retryJob by inject<RetryJobUseCase>()
    val deleteJob by inject<DeleteJobUseCase>()
    val bulkRetry by inject<BulkRetryJobsUseCase>()
    val bulkCancel by inject<BulkCancelJobsUseCase>()
    val bulkDelete by inject<BulkDeleteJobsUseCase>()
    val rerouteJob by inject<RerouteJobUseCase>()

    route("/api/jobs") {

        // GET /api/jobs?state=ENQUEUED&state=PROCESSING&queue=default&payloadType=...&page=0&size=50
        get {
            val query = extractor.extractListJobsQuery(call)
            validation.validateListJobsQuery(query).asErrorResponse()?.let {
                call.respond(HttpStatusCode.BadRequest, it)
                return@get
            }

            val filter = mapper.toJobListFilter(query)
            getList(filter, query.page, query.size).fold(
                onSuccess = { paged ->
                    call.respond(
                        HttpStatusCode.OK,
                        ListJobsResponse(
                            items = paged.items.map(mapper::toView),
                            total = paged.total,
                            page = paged.page,
                            size = paged.size,
                        ),
                    )
                },
                onFailure = { call.respond(HttpStatusCode.InternalServerError, it.message ?: "error") },
            )
        }

        // Bulk endpoints — mounted BEFORE the /{id} block so they don't get captured
        // as job ids. Each handler validates batch size + delegates to its use case.
        post("/bulk-retry") {
            val body = call.receive<BulkIdsRequest>()
            if (body.ids.isEmpty()) {
                call.respond(HttpStatusCode.BadRequest, "ids must not be empty")
                return@post
            }
            if (body.ids.size > BulkIdsRequest.MAX_BATCH_SIZE) {
                call.respond(HttpStatusCode.BadRequest, "batch capped at ${BulkIdsRequest.MAX_BATCH_SIZE}")
                return@post
            }
            val by = call.request.queryParameters["by"]
            bulkRetry(body.ids, by).fold(
                onSuccess = { call.respond(HttpStatusCode.OK, it) },
                onFailure = { call.respond(HttpStatusCode.InternalServerError, it.message ?: "error") },
            )
        }
        post("/bulk-cancel") {
            val body = call.receive<BulkIdsRequest>()
            if (body.ids.isEmpty()) {
                call.respond(HttpStatusCode.BadRequest, "ids must not be empty")
                return@post
            }
            if (body.ids.size > BulkIdsRequest.MAX_BATCH_SIZE) {
                call.respond(HttpStatusCode.BadRequest, "batch capped at ${BulkIdsRequest.MAX_BATCH_SIZE}")
                return@post
            }
            val by = call.request.queryParameters["by"]
            bulkCancel(body.ids, by).fold(
                onSuccess = { call.respond(HttpStatusCode.OK, it) },
                onFailure = { call.respond(HttpStatusCode.InternalServerError, it.message ?: "error") },
            )
        }
        post("/bulk-delete") {
            val body = call.receive<BulkIdsRequest>()
            if (body.ids.isEmpty()) {
                call.respond(HttpStatusCode.BadRequest, "ids must not be empty")
                return@post
            }
            if (body.ids.size > BulkIdsRequest.MAX_BATCH_SIZE) {
                call.respond(HttpStatusCode.BadRequest, "batch capped at ${BulkIdsRequest.MAX_BATCH_SIZE}")
                return@post
            }
            val by = call.request.queryParameters["by"]
            bulkDelete(body.ids, by).fold(
                onSuccess = { call.respond(HttpStatusCode.OK, it) },
                onFailure = { call.respond(HttpStatusCode.InternalServerError, it.message ?: "error") },
            )
        }

        route("/{${JobDescription.JOB_ID_PARAMETER_NAME}}") {

            // GET /api/jobs/{id}
            get {
                val rawId = extractor.extractJobId(call)
                val jobId = runCatching { Uuid.parse(rawId) }.getOrElse {
                    call.respond(HttpStatusCode.BadRequest, "Invalid jobId: $rawId")
                    return@get
                }
                getDetail(jobId).fold(
                    onSuccess = { result ->
                        if (result == null) call.respond(HttpStatusCode.NotFound)
                        else call.respond(
                            HttpStatusCode.OK,
                            GetJobDetailResponse(
                                mapper.toDetail(
                                    job = result.job,
                                    events = result.events,
                                    graphNodes = result.graph.nodes,
                                    graphEdges = result.graph.edges,
                                    graphTruncated = result.graph.truncated,
                                ),
                            ),
                        )
                    },
                    onFailure = { call.respond(HttpStatusCode.InternalServerError, it.message ?: "error") },
                )
            }

            // POST /api/jobs/{id}/retry?by=admin&mode=once
            post("/retry") {
                val rawId = extractor.extractJobId(call)
                val jobId = runCatching { Uuid.parse(rawId) }.getOrElse {
                    call.respond(HttpStatusCode.BadRequest, "Invalid jobId: $rawId")
                    return@post
                }
                // Reuse the actor extractor lambda the cancel extractor already wires
                // ("by" query param OR auth principal). Same audit semantics for both
                // manual actions per DESIGN.md 18.6.
                val by = call.request.queryParameters["by"]
                // ?mode=once → "Retry +1" (grant exactly one more attempt); anything else /
                // absent keeps the default fresh-budget retry (DESIGN.md 9.5).
                val mode = if (call.request.queryParameters["mode"].equals("once", ignoreCase = true)) {
                    RetryMode.ONCE
                } else {
                    RetryMode.FRESH_BUDGET
                }
                retryJob(jobId, by, mode).fold(
                    onSuccess = { result ->
                        val status = when (result) {
                            RetryResult.RETRIED -> HttpStatusCode.OK
                            RetryResult.NOT_FAILED,
                            RetryResult.CONFLICT -> HttpStatusCode.Conflict
                            RetryResult.NOT_FOUND -> HttpStatusCode.NotFound
                        }
                        call.respond(status, RetryJobResponse(jobId = jobId.toString(), result = result))
                    },
                    onFailure = { call.respond(HttpStatusCode.InternalServerError, it.message ?: "error") },
                )
            }

            // DELETE /api/jobs/{id}?by=admin
            delete {
                val rawId = extractor.extractJobId(call)
                val jobId = runCatching { Uuid.parse(rawId) }.getOrElse {
                    call.respond(HttpStatusCode.BadRequest, "Invalid jobId: $rawId")
                    return@delete
                }
                val by = call.request.queryParameters["by"]
                deleteJob(jobId, by).fold(
                    onSuccess = { result ->
                        val status = when (result) {
                            DeleteResult.DELETED -> HttpStatusCode.OK
                            DeleteResult.NOT_TERMINAL -> HttpStatusCode.Conflict
                            DeleteResult.NOT_FOUND -> HttpStatusCode.NotFound
                        }
                        call.respond(status, DeleteJobResponse(jobId = jobId.toString(), result = result))
                    },
                    onFailure = { call.respond(HttpStatusCode.InternalServerError, it.message ?: "error") },
                )
            }

            // POST /api/jobs/{id}/reroute?targetNode=X&targetTag=Y&by=admin
            // Either targetNode or targetTag (or both empty to clear). DESIGN.md 22.2.
            post("/reroute") {
                val rawId = extractor.extractJobId(call)
                val jobId = runCatching { Uuid.parse(rawId) }.getOrElse {
                    call.respond(HttpStatusCode.BadRequest, "Invalid jobId: $rawId")
                    return@post
                }
                val params = call.request.queryParameters
                val node = params["targetNode"]?.takeIf { it.isNotBlank() }
                val tag = params["targetTag"]?.takeIf { it.isNotBlank() }
                val by = params["by"]
                rerouteJob(jobId, node, tag, by).fold(
                    onSuccess = { result ->
                        val status = when (result) {
                            RerouteResult.REROUTED -> HttpStatusCode.OK
                            RerouteResult.ALREADY_TERMINAL,
                            RerouteResult.CONFLICT -> HttpStatusCode.Conflict
                            RerouteResult.NOT_FOUND -> HttpStatusCode.NotFound
                        }
                        call.respond(status, RerouteJobResponse(jobId = jobId.toString(), result = result))
                    },
                    onFailure = { call.respond(HttpStatusCode.InternalServerError, it.message ?: "error") },
                )
            }

            // POST /api/jobs/{id}/cancel?by=admin
            post("/cancel") {
                val cancelQuery = extractor.extractCancelJobQuery(call)
                validation.validateCancelJobQuery(cancelQuery).asErrorResponse()?.let {
                    call.respond(HttpStatusCode.BadRequest, it)
                    return@post
                }

                val jobId = Uuid.parse(cancelQuery.jobId)
                cancelJob(jobId, cancelQuery.by).fold(
                    onSuccess = { result ->
                        val status = when (result) {
                            CancelResult.CANCELLED,
                            CancelResult.CANCEL_REQUESTED -> HttpStatusCode.OK
                            CancelResult.ALREADY_TERMINAL -> HttpStatusCode.Conflict
                            CancelResult.NOT_FOUND -> HttpStatusCode.NotFound
                        }
                        call.respond(status, CancelJobResponse(jobId = jobId.toString(), result = result))
                    },
                    onFailure = { call.respond(HttpStatusCode.InternalServerError, it.message ?: "error") },
                )
            }
        }
    }
}
