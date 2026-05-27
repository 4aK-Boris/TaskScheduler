package cs.trade.scheduler.shared.dto

import kotlinx.serialization.Serializable

/**
 * 400 Bad Request body for failed request validation. `path` is a dotted field path
 * (e.g. "queue", "size"); `message` is the human-readable reason as produced by the
 * validator. Shared so the wasmJs client can deserialize and surface field-level
 * errors in the UI.
 */
@Serializable
public data class ValidationErrorResponse(
    val errors: List<FieldError>,
) {
    @Serializable
    public data class FieldError(
        val path: String,
        val message: String,
    )
}
