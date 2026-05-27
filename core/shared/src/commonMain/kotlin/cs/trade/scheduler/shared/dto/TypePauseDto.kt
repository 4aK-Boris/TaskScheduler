package cs.trade.scheduler.shared.dto

import kotlin.time.Instant
import kotlinx.serialization.Serializable

/** Wire DTO for one paused payload_type. See DESIGN.md 22.1. */
@Serializable
public data class TypePauseDto(
    val payloadType: String,
    val pausedSince: Instant,
    val pausedBy: String,
    val reason: String? = null,
)

@Serializable
public data class ListPausesResponse(val items: List<TypePauseDto>)

@Serializable
public data class PauseTypeRequest(val reason: String? = null)

/**
 * Response for `GET /api/types` — union of ever-enqueued payload types + currently-paused
 * ones. Used by the dashboard pause-form dropdown.
 */
@Serializable
public data class KnownTypesResponse(val items: List<String>)
