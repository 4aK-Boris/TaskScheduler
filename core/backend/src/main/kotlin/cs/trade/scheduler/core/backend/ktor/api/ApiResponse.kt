package cs.trade.scheduler.core.backend.ktor.api

import kotlinx.serialization.Serializable

/**
 * Uniform envelope for all HTTP responses from dashboard-server.
 * Mirrors `cs.trade.core.ktor.api.ApiResponse` from main project.
 */
@Serializable
public sealed interface ApiResponse<out T> {

    @Serializable
    public data class Success<T>(val data: T) : ApiResponse<T>

    @Serializable
    public data class Error(
        val code: String,
        val message: String,
        val details: Map<String, String>? = null,
    ) : ApiResponse<Nothing>
}
