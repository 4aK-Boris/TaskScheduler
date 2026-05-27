package cs.trade.scheduler.dashboard.server.api.routes

import cs.trade.scheduler.dashboard.server.api.mappers.TypePauseApiMapper
import cs.trade.scheduler.dashboard.server.domain.usecases.ListJobTypePausesUseCase
import cs.trade.scheduler.dashboard.server.domain.usecases.ListKnownPayloadTypesUseCase
import cs.trade.scheduler.dashboard.server.domain.usecases.PauseJobTypeUseCase
import cs.trade.scheduler.dashboard.server.domain.usecases.UnpauseJobTypeUseCase
import cs.trade.scheduler.shared.dto.KnownTypesResponse
import cs.trade.scheduler.shared.dto.ListPausesResponse
import cs.trade.scheduler.shared.dto.PauseTypeRequest
import io.ktor.http.HttpStatusCode
import io.ktor.server.auth.UserIdPrincipal
import io.ktor.server.auth.principal
import io.ktor.server.request.receiveNullable
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import org.koin.ktor.ext.inject

/**
 * /api/types sub-routing — job-type level operations (currently just pause/unpause; the
 * "list all known types" endpoint will land alongside the dashboard /types page).
 *
 * Pause/unpause endpoints follow the same actor-attribution pattern as MANUAL_CANCEL:
 * `actor` = basic-auth username (or "anonymous" when auth is disabled — same as JobsRouting).
 * See DESIGN.md 18.6.
 */
public fun Route.configureTypesRouting() {
    val mapper by inject<TypePauseApiMapper>()
    val list by inject<ListJobTypePausesUseCase>()
    val pause by inject<PauseJobTypeUseCase>()
    val unpause by inject<UnpauseJobTypeUseCase>()
    val listKnown by inject<ListKnownPayloadTypesUseCase>()

    route("/api/types") {

        // GET /api/types — union of (ever-enqueued payload types) + (currently paused).
        // Used by the dashboard pause-form dropdown so operators don't have to retype
        // long FQNs from memory.
        get {
            listKnown().fold(
                onSuccess = { types ->
                    call.respond(HttpStatusCode.OK, KnownTypesResponse(items = types))
                },
                onFailure = { call.respond(HttpStatusCode.InternalServerError, it.message ?: "error") },
            )
        }

        // GET /api/types/pauses
        get("/pauses") {
            list().fold(
                onSuccess = { rows ->
                    call.respond(HttpStatusCode.OK, ListPausesResponse(items = rows.map(mapper::toDto)))
                },
                onFailure = { call.respond(HttpStatusCode.InternalServerError, it.message ?: "error") },
            )
        }

        route("/{type}") {

            // POST /api/types/{type}/pause { "reason": "Email service down" }
            post("/pause") {
                val type = call.parameters["type"]
                if (type.isNullOrBlank()) {
                    call.respond(HttpStatusCode.BadRequest, "payload_type path param required")
                    return@post
                }
                val body = call.receiveNullable<PauseTypeRequest>() ?: PauseTypeRequest()
                val actor = call.principal<UserIdPrincipal>()?.name ?: "anonymous"
                pause(type, actor, body.reason).fold(
                    onSuccess = { call.respond(HttpStatusCode.NoContent) },
                    onFailure = { call.respond(HttpStatusCode.InternalServerError, it.message ?: "error") },
                )
            }

            // POST /api/types/{type}/unpause
            post("/unpause") {
                val type = call.parameters["type"]
                if (type.isNullOrBlank()) {
                    call.respond(HttpStatusCode.BadRequest, "payload_type path param required")
                    return@post
                }
                unpause(type).fold(
                    onSuccess = { removed ->
                        if (removed) call.respond(HttpStatusCode.NoContent)
                        else call.respond(HttpStatusCode.NotFound)
                    },
                    onFailure = { call.respond(HttpStatusCode.InternalServerError, it.message ?: "error") },
                )
            }
        }
    }
}
