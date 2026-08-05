package cs.trade.scheduler.dashboard.web.data.repositories

import cs.trade.scheduler.core.frontend.api.ApiClient
import cs.trade.scheduler.dashboard.web.domain.repositories.StatsRepository
import cs.trade.scheduler.shared.dto.StatsOverviewResponse
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.http.isSuccess

public class StatsRepositoryImpl : StatsRepository {

    override suspend fun overview(rangeHours: Int): StatsOverviewResponse {
        // Server accepts the short form (1h / 24h / 7d / …); convert like TypeStatsRepositoryImpl.
        val range = when (rangeHours) {
            1 -> "1h"
            3 -> "3h"
            6 -> "6h"
            12 -> "12h"
            24 -> "24h"
            24 * 3 -> "3d"
            24 * 7 -> "7d"
            24 * 30 -> "30d"
            else -> "${rangeHours}h"
        }
        val resp = ApiClient.http.get("/api/stats/overview?range=$range")
        require(resp.status.isSuccess()) { "HTTP ${resp.status.value} ${resp.status.description}" }
        return resp.body()
    }
}
