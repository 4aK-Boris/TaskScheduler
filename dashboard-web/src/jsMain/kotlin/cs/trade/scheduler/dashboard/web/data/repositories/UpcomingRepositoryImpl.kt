package cs.trade.scheduler.dashboard.web.data.repositories

import cs.trade.scheduler.core.frontend.api.ApiClient
import cs.trade.scheduler.dashboard.web.domain.repositories.UpcomingRepository
import cs.trade.scheduler.shared.dto.UpcomingResponse
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.http.isSuccess

public class UpcomingRepositoryImpl : UpcomingRepository {
    override suspend fun upcoming(withinMinutes: Int): UpcomingResponse {
        val resp = ApiClient.http.get(BASE) { parameter("withinMinutes", withinMinutes) }
        require(resp.status.isSuccess()) { "HTTP ${resp.status.value} ${resp.status.description}" }
        return resp.body()
    }

    private companion object {
        const val BASE = "/api/upcoming"
    }
}
