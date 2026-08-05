package cs.trade.scheduler.dashboard.web.data.repositories

import cs.trade.scheduler.core.frontend.api.ApiClient
import cs.trade.scheduler.dashboard.web.domain.repositories.QueueHealthRepository
import cs.trade.scheduler.shared.dto.QueueHealthDto
import cs.trade.scheduler.shared.dto.QueueHealthResponse
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.http.isSuccess

public class QueueHealthRepositoryImpl : QueueHealthRepository {

    override suspend fun list(): List<QueueHealthDto> {
        val resp = ApiClient.http.get("/api/queues/health")
        require(resp.status.isSuccess()) { "HTTP ${resp.status.value} ${resp.status.description}" }
        return resp.body<QueueHealthResponse>().items
    }
}
