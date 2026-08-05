package cs.trade.scheduler.dashboard.web.data.repositories

import cs.trade.scheduler.core.frontend.api.ApiClient
import cs.trade.scheduler.dashboard.web.domain.repositories.WorkersRepository
import cs.trade.scheduler.shared.dto.ListWorkersResponse
import cs.trade.scheduler.shared.dto.WorkerDto
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.http.isSuccess

public class WorkersRepositoryImpl : WorkersRepository {
    override suspend fun list(): List<WorkerDto> {
        val resp = ApiClient.http.get("/api/workers")
        require(resp.status.isSuccess()) { "HTTP ${resp.status.value} ${resp.status.description}" }
        return resp.body<ListWorkersResponse>().items
    }
}
