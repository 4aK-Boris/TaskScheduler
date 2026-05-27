package cs.trade.scheduler.shared.dto

import kotlinx.serialization.Serializable

/**
 * Body for bulk retry / cancel / delete endpoints. Server caps batch size at
 * [MAX_BATCH_SIZE]; requests larger than that get 400.
 */
@Serializable
public data class BulkIdsRequest(val ids: List<String>) {
    public companion object {
        public const val MAX_BATCH_SIZE: Int = 100
    }
}

/**
 * Aggregated outcome for a bulk action. `byOutcome` keys are the per-action result
 * enum names (`RETRIED`, `NOT_FAILED`, `CONFLICT`, `NOT_FOUND` for retry; etc.) — keeps
 * the wire format flat so a single response shape works for all three actions without
 * polymorphic deserialisation.
 *
 *  - [total] = `ids.size` requested
 *  - [ok] = items that landed the success outcome (`RETRIED` / `CANCELLED` /
 *    `CANCEL_REQUESTED` / `DELETED`). Convenience for the UI.
 *  - [byOutcome] = full per-outcome counts including failure modes
 */
@Serializable
public data class BulkActionResponse(
    val total: Int,
    val ok: Int,
    val byOutcome: Map<String, Int>,
)
