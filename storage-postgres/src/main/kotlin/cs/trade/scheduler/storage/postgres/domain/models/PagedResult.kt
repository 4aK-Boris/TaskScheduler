package cs.trade.scheduler.storage.postgres.domain.models

/**
 * Generic paged read result for dashboard list endpoints. `total` is the unfiltered
 * count for the current filter (one extra COUNT query) so the UI can render
 * "showing N of total" labels.
 */
public data class PagedResult<T>(
    val items: List<T>,
    val total: Long,
    val page: Int,
    val size: Int,
)

/**
 * Filter for `JobRepository.findAll`. All fields are optional — `null`/empty means
 * "no filter on this axis". Combined with AND.
 *
 * [attemptsExhausted] is the DLQ predicate (see DESIGN.md 18.6 / dashboard `DLQ` toggle):
 *  - `true` → `attempts >= max_attempts` (auto-retry won't fire — operator must intervene)
 *  - `false` → `attempts < max_attempts` (still has budget left)
 *  - `null` → don't filter
 *
 * Usually paired with `states = {FAILED}` in the dashboard "DLQ" view, but the column
 * predicate is independent and works for any state if a caller wants something exotic.
 */
public data class JobListFilter(
    val states: Set<cs.trade.scheduler.shared.JobState>? = null,
    val queue: String? = null,
    val payloadType: String? = null,
    val attemptsExhausted: Boolean? = null,
)
