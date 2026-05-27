package cs.trade.scheduler.dashboard.web.data.repositories

import cs.trade.scheduler.core.frontend.api.ApiClient
import cs.trade.scheduler.dashboard.web.domain.repositories.RecurringRepository
import cs.trade.scheduler.shared.dto.ListRecurringJobsResponse
import cs.trade.scheduler.shared.dto.RecurringJobDto
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.http.HttpStatusCode
import io.ktor.http.isSuccess

public class RecurringRepositoryImpl : RecurringRepository {

    override suspend fun list(): List<RecurringJobDto> {
        val resp = ApiClient.http.get(BASE)
        require(resp.status.isSuccess()) { "HTTP ${resp.status.value} ${resp.status.description}" }
        return resp.body<ListRecurringJobsResponse>().items
    }

    override suspend fun enable(id: String): Boolean {
        val resp = ApiClient.http.post("$BASE/$id/enable")
        return when (resp.status) {
            HttpStatusCode.NotFound -> false
            else -> resp.status.isSuccess()
        }
    }

    override suspend fun disable(id: String): Boolean {
        val resp = ApiClient.http.post("$BASE/$id/disable")
        return when (resp.status) {
            HttpStatusCode.NotFound -> false
            else -> resp.status.isSuccess()
        }
    }

    private companion object {
        const val BASE = "/api/recurring"
    }
}
