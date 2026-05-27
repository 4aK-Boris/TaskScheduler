package cs.trade.scheduler.storage.postgres.domain.models

import kotlin.time.Instant

/**
 * One pause record in `job_type_pause`. Identified by [payloadType] (PK) — pausing is
 * type-wide, not per-job. See DESIGN.md 22.1.
 */
public data class JobTypePauseRow(
    val payloadType: String,
    val pausedSince: Instant,
    val pausedBy: String,
    val reason: String?,
)
