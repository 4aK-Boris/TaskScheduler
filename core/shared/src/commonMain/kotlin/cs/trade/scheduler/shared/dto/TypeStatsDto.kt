package cs.trade.scheduler.shared.dto

import kotlinx.serialization.Serializable

/**
 * Per-(payload_type, queue) aggregate stats over a trailing time window.
 * Backs the dashboard "Type Stats" page (DESIGN.md 22.4).
 *
 * Duration fields are nullable: terminal rows whose `duration_ms` column is NULL
 * (legacy rows pre-duration tracking, or never-started cancelled rows) drop out of
 * the aggregate. When no rows in the window have a non-null duration the whole
 * column collapses to null.
 */
@Serializable
public data class TypeStatsDto(
    val payloadType: String,
    val queue: String,
    val successCount: Long,
    val failedCount: Long,
    val cancelledCount: Long,
    /** `sum(attempts - 1)` over SUCCEEDED + FAILED rows — clamped at 0 per row. */
    val retryCount: Long,
    val avgDurationMs: Long?,
    val minDurationMs: Long?,
    val maxDurationMs: Long?,
    /** `percentile_cont(0.95) WITHIN GROUP (ORDER BY duration_ms)` — Postgres native. */
    val p95DurationMs: Long?,
)

@Serializable
public data class TypeStatsResponse(
    val items: List<TypeStatsDto>,
    val rangeHours: Int,
)

/**
 * Predefined ranges the dashboard's selector exposes. The wire format is the enum
 * name, but the REST endpoint also accepts the short form `1h`, `24h`, `7d`, `30d`
 * via the `range` query param — see `StatsRouting.kt`.
 */
@Serializable
public enum class TypeStatsRange {
    LAST_1_HOUR,
    LAST_3_HOURS,
    LAST_6_HOURS,
    LAST_12_HOURS,
    LAST_24_HOURS,
    LAST_3_DAYS,
    LAST_7_DAYS,
    LAST_30_DAYS,
}
