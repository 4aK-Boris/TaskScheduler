package cs.trade.scheduler.dashboard.web.data.repositories

import cs.trade.scheduler.core.frontend.api.ApiClient
import cs.trade.scheduler.dashboard.web.domain.repositories.TypesRepository
import cs.trade.scheduler.shared.dto.KnownTypesResponse
import cs.trade.scheduler.shared.dto.ListPausesResponse
import cs.trade.scheduler.shared.dto.PauseTypeRequest
import cs.trade.scheduler.shared.dto.TypePauseDto
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.isSuccess

public class TypesRepositoryImpl : TypesRepository {

    override suspend fun listPaused(): List<TypePauseDto> {
        val resp = ApiClient.http.get("$BASE/pauses")
        require(resp.status.isSuccess()) { "HTTP ${resp.status.value} ${resp.status.description}" }
        return resp.body<ListPausesResponse>().items
    }

    override suspend fun listKnown(): List<String> {
        val resp = ApiClient.http.get(BASE)
        require(resp.status.isSuccess()) { "HTTP ${resp.status.value} ${resp.status.description}" }
        return resp.body<KnownTypesResponse>().items
    }

    override suspend fun pause(payloadType: String, reason: String?): Boolean {
        val resp = ApiClient.http.post("$BASE/$payloadType/pause") {
            contentType(ContentType.Application.Json)
            setBody(PauseTypeRequest(reason = reason))
        }
        return resp.status.isSuccess()
    }

    override suspend fun unpause(payloadType: String): Boolean {
        val resp = ApiClient.http.post("$BASE/$payloadType/unpause")
        return when (resp.status) {
            HttpStatusCode.NotFound -> false
            else -> resp.status.isSuccess()
        }
    }

    private companion object {
        const val BASE = "/api/types"
    }
}
