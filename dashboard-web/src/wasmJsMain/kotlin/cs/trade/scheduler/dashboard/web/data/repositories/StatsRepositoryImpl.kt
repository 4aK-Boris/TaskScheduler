package cs.trade.scheduler.dashboard.web.data.repositories

import cs.trade.scheduler.core.frontend.api.ApiClient
import cs.trade.scheduler.dashboard.web.domain.repositories.StatsRepository
import cs.trade.scheduler.shared.dto.StatsOverviewResponse
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.http.isSuccess

public class StatsRepositoryImpl : StatsRepository {

    override suspend fun overview(): StatsOverviewResponse {
        val resp = ApiClient.http.get("/api/stats/overview")
        require(resp.status.isSuccess()) { "HTTP ${resp.status.value} ${resp.status.description}" }
        return resp.body()
    }
}
