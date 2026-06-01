package cs.trade.scheduler.shared

/**
 * Sortable columns for the jobs list (`/api/jobs?sort=…&dir=…`). Shared so the dashboard client and
 * the server agree on the names; the storage layer maps each to a `job` column for `ORDER BY`.
 * `null` sort (no param) keeps the default `updated_at DESC`.
 */
public enum class JobSortField {
    CREATED,
    UPDATED,
    STARTED,
    ATTEMPTS,
    PRIORITY,
    QUEUE,
    TYPE,
    STATE,
}
