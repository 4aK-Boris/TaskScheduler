package cs.trade.scheduler.dashboard.server.api.routes

import cs.trade.scheduler.dashboard.server.domain.usecases.GetQueuesHealthUseCase
import cs.trade.scheduler.shared.dto.QueueHealthResponse
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.route
import org.koin.ktor.ext.inject

/**
 * /api/queues sub-routing. Backpressure indicator endpoint (DESIGN.md 20.10) — small,
 * polled every 15s by the dashboard so the JobList header can render a green / yellow /
 * red badge per queue without each page load running an aggregate query.
 *
 * Single GET for now — pause/resume / cancel-all are operator actions that already exist
 * on JobsRouting + TypesRouting and don't belong here.
 */
public fun Route.configureQueuesRouting() {
    val getQueuesHealth by inject<GetQueuesHealthUseCase>()

    route("/api/queues") {
        // GET /api/queues/health — list of {queue, depth, status} sorted by depth DESC.
        // Empty queues (no non-terminal rows) are absent; UI defaults them to "no badge".
        get("/health") {
            getQueuesHealth().fold(
                onSuccess = { rows ->
                    call.respond(HttpStatusCode.OK, QueueHealthResponse(items = rows))
                },
                onFailure = { call.respond(HttpStatusCode.InternalServerError, it.message ?: "error") },
            )
        }
    }
}
