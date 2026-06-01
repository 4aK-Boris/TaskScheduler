package cs.trade.scheduler.dashboard.server.api.dto

/**
 * Module-local request DTOs for /api/jobs. Response shapes live in :core:shared so the
 * wasmJs frontend can deserialise them without duplication; request shapes stay server-
 * side because Konform validators reference them.
 */

/**
 * Raw form of GET /api/jobs query params, fed straight from the extractor before
 * domain coercion. Validation rules live in `JobValidation.validateListJobsQuery`.
 * Defaults are applied here — Konform asserts the bounds; it does not pick them.
 */
public data class ListJobsQuery(
    val states: List<String>,
    val queue: String?,
    val payloadType: String?,
    val page: Int,
    val size: Int,
    /**
     * DLQ predicate: when `true`, restrict to rows whose auto-retry budget is gone
     * (`attempts >= max_attempts`). `null` = no filter on the attempts axis.
     */
    val attemptsExhausted: Boolean?,
    /**
     * "Upcoming" window in minutes: restrict to future-dated jobs scheduled within the next
     * N minutes, soonest-first. `null` = no scheduling filter.
     */
    val scheduledWithinMinutes: Int?,
)

/**
 * Raw form of POST /api/jobs/{id}/cancel. `jobId` is the path segment as a string;
 * `by` is the actor (principal-or-?by=) with an upper length cap so a malicious caller
 * can't fill the audit log with megabyte names.
 */
public data class CancelJobQuery(
    val jobId: String,
    val by: String?,
)
