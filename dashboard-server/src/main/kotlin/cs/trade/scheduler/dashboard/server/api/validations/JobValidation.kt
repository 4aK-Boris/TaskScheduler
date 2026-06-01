package cs.trade.scheduler.dashboard.server.api.validations

import cs.trade.scheduler.core.backend.ktor.validation.BaseValidation
import cs.trade.scheduler.dashboard.server.api.dto.CancelJobQuery
import cs.trade.scheduler.dashboard.server.api.dto.ListJobsQuery
import cs.trade.scheduler.dashboard.server.api.extractors.JobExtractor
import cs.trade.scheduler.shared.JobSortField
import cs.trade.scheduler.shared.JobState
import io.konform.validation.Validation
import io.konform.validation.constraints.enum
import io.konform.validation.constraints.maxItems
import io.konform.validation.constraints.maxLength
import io.konform.validation.constraints.maximum
import io.konform.validation.constraints.minimum
import io.konform.validation.constraints.notBlank
import io.konform.validation.constraints.pattern
import io.konform.validation.constraints.uuid
import org.koin.core.annotation.Single

/**
 * Konform rules for /api/jobs request payloads. One validator per query DTO; routes
 * call the relevant `validateXxx(query)` and short-circuit on [io.konform.validation.Invalid].
 *
 * Bounds are deliberately tighter than the underlying types allow:
 *  - `page <= 10_000` rejects offset-pagination abuse (don't let a client ask for page
 *    1_000_000 and force a multi-million-row scan).
 *  - `size <= MAX_PAGE_SIZE` matches the Postgres LIMIT cap.
 *  - `queue`/`payloadType` patterns reject newlines and weird chars that would let
 *    a caller smuggle log injection through the audit trail.
 *  - `by` capped at 128 — actor names land in `job_event.actor` (varchar 128).
 */
@Single
public class JobValidation : BaseValidation {

    public val validateListJobsQuery: Validation<ListJobsQuery> = Validation {
        ListJobsQuery::states {
            maxItems(JobState.entries.size)
        }
        ListJobsQuery::states onEach {
            enum<JobState>()
        }
        ListJobsQuery::queue ifPresent {
            notBlank()
            maxLength(QUEUE_MAX_LENGTH)
            pattern(IDENT_PATTERN) hint "must match $IDENT_PATTERN"
        }
        ListJobsQuery::payloadType ifPresent {
            notBlank()
            maxLength(PAYLOAD_TYPE_MAX_LENGTH)
            pattern(FQN_PATTERN) hint "must look like a fully-qualified class name"
        }
        ListJobsQuery::page {
            minimum(0)
            maximum(MAX_PAGE_INDEX)
        }
        ListJobsQuery::size {
            minimum(1)
            maximum(JobExtractor.MAX_PAGE_SIZE)
        }
        ListJobsQuery::scheduledWithinMinutes ifPresent {
            minimum(1)
            maximum(MAX_SCHEDULED_WITHIN_MINUTES)
        }
        ListJobsQuery::sortBy ifPresent {
            enum<JobSortField>()
        }
    }

    public val validateCancelJobQuery: Validation<CancelJobQuery> = Validation {
        CancelJobQuery::jobId {
            uuid()
        }
        CancelJobQuery::by ifPresent {
            notBlank()
            maxLength(ACTOR_MAX_LENGTH)
        }
    }

    public companion object {
        public const val QUEUE_MAX_LENGTH: Int = 64
        public const val PAYLOAD_TYPE_MAX_LENGTH: Int = 256
        public const val ACTOR_MAX_LENGTH: Int = 128
        public const val MAX_PAGE_INDEX: Int = 10_000

        // Upcoming-window cap: 31 days. Generous enough for any "what's coming up" view,
        // tight enough to keep the scheduled_at range scan bounded.
        public const val MAX_SCHEDULED_WITHIN_MINUTES: Int = 44_640

        // `letters/digits/_/-/./:` — enough for `email`, `default`, `cs.trade:high-prio`.
        // Rejects whitespace, control chars, quotes, slashes.
        public const val IDENT_PATTERN: String = "[A-Za-z0-9_.:\\-]+"

        // Reluctant FQN: one or more dot-separated segments of letters/digits/underscore
        // with an optional `$` for inner classes. Permissive enough for Kotlin and Java
        // payload classes, strict enough to reject log-injection attempts.
        public const val FQN_PATTERN: String = "[A-Za-z_][A-Za-z0-9_]*(\\.[A-Za-z_][A-Za-z0-9_$]*)*"
    }
}
