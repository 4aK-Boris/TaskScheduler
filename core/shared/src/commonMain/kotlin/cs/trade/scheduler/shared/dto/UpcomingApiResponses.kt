package cs.trade.scheduler.shared.dto

import cs.trade.scheduler.shared.JobState
import kotlin.time.Instant
import kotlinx.serialization.Serializable

// Wire DTOs for /api/upcoming — a forward agenda of when tasks will run next. Shared between
// :dashboard-server and :dashboard-web.

/** Where an upcoming run comes from: an expanded cron slot of a recurring definition, or a real
 *  future-dated job row (one-off scheduleAt, or a failed job waiting on its backoff retry). */
public enum class UpcomingSource { RECURRING, JOB }

/**
 * One predicted run at [at]. For [UpcomingSource.RECURRING] the same definition appears once per
 * cron slot in the window (repetitions are expected) — [id] is the recurring id, [cron] is set,
 * [state] is null (no job row exists yet). For [UpcomingSource.JOB] [id] is the job's UUID and
 * [state] its current state ([cron] null).
 */
@Serializable
public data class UpcomingOccurrenceDto(
    val at: Instant,
    val source: UpcomingSource,
    val payloadType: String,
    val queue: String,
    val id: String,
    val cron: String? = null,
    val state: JobState? = null,
)

@Serializable
public data class UpcomingResponse(
    val items: List<UpcomingOccurrenceDto>,
    // True when the merged result was capped (more runs exist in the window than the cap, or a
    // single recurring hit its per-definition cap) — the UI shows a "showing the soonest N" note.
    val truncated: Boolean,
    val windowMinutes: Int,
)
