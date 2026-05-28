package cs.trade.scheduler.dashboard.web.data.repositories

import cs.trade.scheduler.core.frontend.api.ApiClient
import cs.trade.scheduler.dashboard.web.domain.repositories.TypeStatsRepository
import cs.trade.scheduler.shared.dto.TypeStatsResponse
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.http.isSuccess

public class TypeStatsRepositoryImpl : TypeStatsRepository {

    override suspend fun list(rangeHours: Int): TypeStatsResponse {
        // Server accepts the short form (1h / 24h / 7d / 30d) — convert the user's
        // numeric hours to the canonical short form the server understands. Anything
        // outside the four canonical buckets falls back to plain `${h}h` which the
        // server's rangeToHours returns null for; that downgrades to its 24h default
        // (and logs a warning) — acceptable because the frontend only ever sends
        // pre-defined buckets via TypeStatsRange.
        val range = when (rangeHours) {
            1 -> "1h"
            24 -> "24h"
            24 * 7 -> "7d"
            24 * 30 -> "30d"
            else -> "${rangeHours}h"
        }
        val resp = ApiClient.http.get("/api/stats/types?range=$range")
        require(resp.status.isSuccess()) { "HTTP ${resp.status.value} ${resp.status.description}" }
        return resp.body()
    }
}
