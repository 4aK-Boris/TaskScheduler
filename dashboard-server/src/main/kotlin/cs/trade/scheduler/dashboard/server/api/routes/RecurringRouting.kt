@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package cs.trade.scheduler.dashboard.server.api.routes

import cs.trade.scheduler.dashboard.server.api.extractors.RecurringExtractor
import cs.trade.scheduler.dashboard.server.api.mappers.RecurringApiMapper
import cs.trade.scheduler.dashboard.server.domain.usecases.DisableRecurringJobUseCase
import cs.trade.scheduler.dashboard.server.domain.usecases.EnableRecurringJobUseCase
import cs.trade.scheduler.dashboard.server.domain.usecases.ListRecurringJobsUseCase
import cs.trade.scheduler.dashboard.server.domain.usecases.ListRecurringRunsUseCase
import cs.trade.scheduler.dashboard.server.domain.usecases.TriggerRecurringJobUseCase
import cs.trade.scheduler.shared.dto.ListRecurringJobsResponse
import cs.trade.scheduler.shared.dto.ToggleRecurringResponse
import cs.trade.scheduler.shared.dto.TriggerRecurringResponse
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import org.koin.ktor.ext.inject

// /api/recurring sub-routing.
public fun Route.configureRecurringRouting() {
    val extractor by inject<RecurringExtractor>()
    val mapper by inject<RecurringApiMapper>()
    val list by inject<ListRecurringJobsUseCase>()
    val listRuns by inject<ListRecurringRunsUseCase>()
    val enable by inject<EnableRecurringJobUseCase>()
    val disable by inject<DisableRecurringJobUseCase>()
    val trigger by inject<TriggerRecurringJobUseCase>()

    route("/api/recurring") {

        // GET /api/recurring
        get {
            list().fold(
                onSuccess = { rows ->
                    // Second query attaches each definition's live-or-latest run, so the screen can
                    // show what's running and link straight to it. Best-effort: a failure here
                    // leaves the runs out rather than failing the whole listing, which is still
                    // useful on its own.
                    val runs = listRuns(rows.map { it.id }).getOrDefault(emptyMap())
                    call.respond(
                        HttpStatusCode.OK,
                        ListRecurringJobsResponse(items = rows.map { mapper.toDto(it, runs[it.id]) }),
                    )
                },
                onFailure = { call.respond(HttpStatusCode.InternalServerError, it.message ?: "error") },
            )
        }

        route("/{${RecurringExtractor.ID_PARAMETER_NAME}}") {

            // POST /api/recurring/{id}/enable
            post("/enable") {
                val id = extractor.extractId(call)
                enable(id).fold(
                    onSuccess = { ok ->
                        if (ok) call.respond(HttpStatusCode.OK, ToggleRecurringResponse(id, enabled = true))
                        else call.respond(HttpStatusCode.NotFound)
                    },
                    onFailure = { call.respond(HttpStatusCode.InternalServerError, it.message ?: "error") },
                )
            }

            // POST /api/recurring/{id}/disable
            post("/disable") {
                val id = extractor.extractId(call)
                disable(id).fold(
                    onSuccess = { ok ->
                        if (ok) call.respond(HttpStatusCode.OK, ToggleRecurringResponse(id, enabled = false))
                        else call.respond(HttpStatusCode.NotFound)
                    },
                    onFailure = { call.respond(HttpStatusCode.InternalServerError, it.message ?: "error") },
                )
            }

            // POST /api/recurring/{id}/trigger — fire the definition once now, off-schedule.
            post("/trigger") {
                val id = extractor.extractId(call)
                trigger(id).fold(
                    onSuccess = { jobId ->
                        if (jobId == null) call.respond(HttpStatusCode.NotFound)
                        else call.respond(
                            HttpStatusCode.OK,
                            TriggerRecurringResponse(id = id, jobId = jobId.toString()),
                        )
                    },
                    onFailure = { call.respond(HttpStatusCode.InternalServerError, it.message ?: "error") },
                )
            }
        }
    }
}
