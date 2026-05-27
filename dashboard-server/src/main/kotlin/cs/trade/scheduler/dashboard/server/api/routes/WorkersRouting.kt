package cs.trade.scheduler.dashboard.server.api.routes

import cs.trade.scheduler.dashboard.server.api.mappers.WorkerApiMapper
import cs.trade.scheduler.dashboard.server.domain.usecases.ListWorkersUseCase
import cs.trade.scheduler.shared.dto.ListWorkersResponse
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.route
import org.koin.ktor.ext.inject

public fun Route.configureWorkersRouting() {
    val list by inject<ListWorkersUseCase>()
    val mapper by inject<WorkerApiMapper>()

    route("/api/workers") {
        get {
            list().fold(
                onSuccess = { rows ->
                    call.respond(HttpStatusCode.OK, ListWorkersResponse(items = rows.map(mapper::toDto)))
                },
                onFailure = { call.respond(HttpStatusCode.InternalServerError, it.message ?: "error") },
            )
        }
    }
}
