package cs.trade.scheduler.dashboard.server.api.routes

import cs.trade.scheduler.dashboard.server.domain.usecases.GetStatsOverviewUseCase
import cs.trade.scheduler.shared.dto.StatsOverviewResponse
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.route
import org.koin.ktor.ext.inject

public fun Route.configureStatsRouting() {
    val getOverview by inject<GetStatsOverviewUseCase>()

    route("/api/stats") {
        get("/overview") {
            getOverview().fold(
                onSuccess = { c ->
                    call.respond(
                        HttpStatusCode.OK,
                        StatsOverviewResponse(
                            enqueued = c.enqueued,
                            processing = c.processing,
                            awaitingRetry = c.awaitingRetry,
                            awaitingDeps = c.awaitingDeps,
                            scheduled = c.scheduled,
                            succeeded = c.succeeded,
                            failed = c.failed,
                            cancelled = c.cancelled,
                        ),
                    )
                },
                onFailure = { call.respond(HttpStatusCode.InternalServerError, it.message ?: "error") },
            )
        }
    }
}
