package cs.trade.scheduler.dashboard.server.api.validations

import cs.trade.scheduler.shared.dto.ValidationErrorResponse
import io.konform.validation.Invalid
import io.konform.validation.ValidationResult

/**
 * Adapter from Konform's [Invalid] to the wire-level [ValidationErrorResponse]. Lives
 * here (not in JobApiMapper) because every validation in dashboard-server uses the
 * same shape — no point teaching each per-group mapper to do it.
 *
 * `dataPath` already gives a JSONPath-ish ".queue" / ".states[0]" string; we drop the
 * leading dot so the path matches the request field name directly ("queue", "states[0]").
 */
public fun Invalid.toResponse(): ValidationErrorResponse = ValidationErrorResponse(
    errors = errors.map { err ->
        ValidationErrorResponse.FieldError(
            path = err.dataPath.removePrefix("."),
            message = err.message,
        )
    },
)

/**
 * Convenience for routes: returns the response only when invalid, so callers can write
 * `validation(query).asErrorResponse()?.let { respond it; return }`.
 */
public fun <T> ValidationResult<T>.asErrorResponse(): ValidationErrorResponse? =
    (this as? Invalid)?.toResponse()
