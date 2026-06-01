package cs.trade.scheduler.dashboard.server.api.routes

import cs.trade.scheduler.dashboard.server.domain.usecases.GetStatsOverviewUseCase
import cs.trade.scheduler.dashboard.server.domain.usecases.GetTypesStatsUseCase
import cs.trade.scheduler.shared.dto.StatsOverviewResponse
import cs.trade.scheduler.shared.dto.TypeStatsResponse
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.route
import org.slf4j.LoggerFactory
import org.koin.ktor.ext.inject

public fun Route.configureStatsRouting() {
    val getOverview by inject<GetStatsOverviewUseCase>()
    val getTypesStats by inject<GetTypesStatsUseCase>()
    val log = LoggerFactory.getLogger("cs.trade.scheduler.dashboard.server.stats")

    route("/api/stats") {
        get("/overview") {
            // `range` (short form 1h/24h/7d/…) windows the terminal outcome counts; live states
            // ignore it. Unrecognised / absent → 24h default, same as /types.
            val raw = call.request.queryParameters["range"]
            val rangeHours = raw?.let { rangeToHours(it) } ?: run {
                if (raw != null) log.warn("Unrecognised /api/stats/overview range='{}'; falling back to 24h", raw)
                DEFAULT_RANGE_HOURS
            }
            getOverview(rangeHours).fold(
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

        // GET /api/stats/types?range=24h  — per-payload-type aggregates (DESIGN.md 22.4).
        // `range` accepts short forms only (1h, 3h, 6h, 12h, 24h, 3d, 7d, 30d); unrecognised values fall
        // back to the 24h default with a warning so the operator's typo doesn't 4xx the
        // page. Invalid ranges out of the [1..720] window still 4xx via the UseCase's
        // require() so a hand-crafted curl can't drag the dashboard into a seq scan.
        get("/types") {
            val raw = call.request.queryParameters["range"]
            val rangeHours = raw?.let { rangeToHours(it) } ?: run {
                if (raw != null) log.warn("Unrecognised /api/stats/types range='{}'; falling back to 24h", raw)
                DEFAULT_RANGE_HOURS
            }
            getTypesStats(rangeHours).fold(
                onSuccess = { rows ->
                    call.respond(HttpStatusCode.OK, TypeStatsResponse(items = rows, rangeHours = rangeHours))
                },
                onFailure = { call.respond(HttpStatusCode.BadRequest, it.message ?: "error") },
            )
        }
    }
}

private const val DEFAULT_RANGE_HOURS: Int = 24

/**
 * `"1h"` → 1, `"3h"` → 3, `"6h"` → 6, `"12h"` → 12, `"24h"` → 24, `"3d"` → 72, `"7d"` → 168, `"30d"` → 720.
 * Returns null on any unrecognised input so the caller can decide the fallback.
 */
private fun rangeToHours(range: String): Int? = when (range.lowercase()) {
    "1h" -> 1
    "3h" -> 3
    "6h" -> 6
    "12h" -> 12
    "24h" -> 24
    "3d" -> 24 * 3
    "7d" -> 24 * 7
    "30d" -> 24 * 30
    else -> null
}
