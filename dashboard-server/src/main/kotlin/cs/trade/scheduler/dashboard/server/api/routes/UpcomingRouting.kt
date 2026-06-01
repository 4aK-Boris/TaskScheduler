package cs.trade.scheduler.dashboard.server.api.routes

import cs.trade.scheduler.dashboard.server.domain.usecases.GetUpcomingUseCase
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.route
import org.koin.ktor.ext.inject

// /api/upcoming — forward agenda of predicted task runs (recurring cron slots + future-dated jobs),
// soonest-first. Single GET; the window is a clamped query param.
public fun Route.configureUpcomingRouting() {
    val getUpcoming by inject<GetUpcomingUseCase>()

    route("/api/upcoming") {
        // GET /api/upcoming?withinMinutes=60
        get {
            val window = call.request.queryParameters["withinMinutes"]
                ?.toIntOrNull()
                ?.coerceIn(1, MAX_WINDOW_MINUTES)
                ?: DEFAULT_WINDOW_MINUTES
            getUpcoming(window).fold(
                onSuccess = { call.respond(HttpStatusCode.OK, it) },
                onFailure = { call.respond(HttpStatusCode.InternalServerError, it.message ?: "error") },
            )
        }
    }
}

private const val DEFAULT_WINDOW_MINUTES = 60
private const val MAX_WINDOW_MINUTES = 44_640 // 31 days — matches the jobs scheduledWithinMinutes cap
