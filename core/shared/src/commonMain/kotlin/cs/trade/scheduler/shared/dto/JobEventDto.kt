package cs.trade.scheduler.shared.dto

import cs.trade.scheduler.shared.JobState
import kotlin.time.Instant
import kotlinx.serialization.Serializable

/** One row from job_event. See DESIGN.md section 6. */
@Serializable
public data class JobEventDto(
    val id: Long,
    val jobId: String,
    val eventType: String,        // ENQUEUED, STARTED, SUCCEEDED, FAILED, RETRY, TIMEOUT, CANCELLED, MANUAL_*
    val prevState: JobState?,
    val newState: JobState?,
    val actor: String?,
    val errorMsg: String?,
    val errorStack: String?,
    val occurredAt: Instant,
)
