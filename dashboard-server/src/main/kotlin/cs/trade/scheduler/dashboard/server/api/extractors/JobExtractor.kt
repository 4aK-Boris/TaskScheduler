package cs.trade.scheduler.dashboard.server.api.extractors

import cs.trade.scheduler.core.backend.EMPTY_STRING
import cs.trade.scheduler.dashboard.server.api.descriptions.JobDescription
import cs.trade.scheduler.dashboard.server.api.dto.CancelJobQuery
import cs.trade.scheduler.dashboard.server.api.dto.ListJobsQuery
import io.ktor.server.application.ApplicationCall
import io.ktor.server.auth.UserIdPrincipal
import io.ktor.server.auth.principal
import org.koin.core.annotation.Single

/**
 * Pulls request params from [ApplicationCall] into raw query DTOs. No business
 * validation here — that lives in `JobValidation` (Konform). No clamping either:
 * the validator must see the user's actual input so it can produce a 400 with the
 * real message ("size must be at most 200") rather than silently quietly accepting
 * `?size=999999`.
 *
 * `EMPTY_STRING` fallback for `jobId` lets the validator produce a friendly "must
 * be a valid UUID" error rather than a 500 from `Uuid.parse("")`.
 */
@Single
public class JobExtractor {

    public fun extractJobId(call: ApplicationCall): String =
        call.parameters[JobDescription.JOB_ID_PARAMETER_NAME] ?: EMPTY_STRING

    /**
     * Raw form for the validator. `states` is the literal list of `?state=...` values
     * (potentially garbage); the validator's `enum<JobState>()` constraint rejects bad
     * entries. Defaults for missing `page`/`size` are applied here because they're a
     * server convention, not a validation rule.
     */
    public fun extractListJobsQuery(call: ApplicationCall): ListJobsQuery {
        val params = call.request.queryParameters
        return ListJobsQuery(
            states = params.getAll("state").orEmpty(),
            queue = params["queue"]?.takeIf { it.isNotBlank() },
            payloadType = params["payloadType"]?.takeIf { it.isNotBlank() },
            page = params["page"]?.toIntOrNull() ?: DEFAULT_PAGE,
            size = params["size"]?.toIntOrNull() ?: DEFAULT_PAGE_SIZE,
            // `attemptsExhausted=true` flips the DLQ filter on; absent param leaves the
            // attempts axis unfiltered. Garbage values fall through to null (don't 400 —
            // the parameter is operator-supplied via a dashboard toggle, missing = off).
            attemptsExhausted = params["attemptsExhausted"]?.toBooleanStrictOrNull(),
            // "Upcoming" window (minutes ahead). Garbage falls through to null (filter off);
            // the validator bounds the accepted range.
            scheduledWithinMinutes = params["scheduledWithinMinutes"]?.toIntOrNull(),
            // `?sort=STARTED&dir=asc`. Bad `sort` is rejected by the validator's enum check; `dir`
            // defaults to descending (only `dir=asc` flips it).
            sortBy = params["sort"]?.takeIf { it.isNotBlank() },
            sortAscending = params["dir"].equals("asc", ignoreCase = true),
        )
    }

    public fun extractCancelJobQuery(call: ApplicationCall): CancelJobQuery =
        CancelJobQuery(
            jobId = extractJobId(call),
            by = extractActor(call),
        )

    /**
     * Actor for the cancel audit row (and any future MANUAL_* events). Priority:
     *   1. `call.principal<UserIdPrincipal>().name` — set by the BasicAuth plugin when
     *      the route lives under `authenticate("dashboard") { ... }`. Most trustworthy
     *      source: the auth layer already verified the credentials.
     *   2. `?by=` query param — explicit override for unauthenticated dev runs and for
     *      service-to-service callers behind their own auth (e.g. an internal admin tool
     *      passing the operator's user id).
     *
     * Returns `null` when neither is present — the audit row then writes `CANCELLED`
     * (no MANUAL_ prefix) rather than misattributing the action.
     */
    public fun extractActor(call: ApplicationCall): String? {
        call.principal<UserIdPrincipal>()?.name?.takeIf { it.isNotBlank() }?.let { return it }
        return call.request.queryParameters["by"]?.takeIf { it.isNotBlank() }
    }

    public companion object {
        public const val DEFAULT_PAGE: Int = 0
        public const val DEFAULT_PAGE_SIZE: Int = 50
        public const val MAX_PAGE_SIZE: Int = 200
    }
}
