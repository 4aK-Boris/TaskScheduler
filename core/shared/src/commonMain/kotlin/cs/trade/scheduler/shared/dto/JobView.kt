package cs.trade.scheduler.shared.dto

import cs.trade.scheduler.shared.JobPriority
import cs.trade.scheduler.shared.JobState
import kotlin.time.Instant
import kotlinx.serialization.Serializable

/**
 * Single row in the JobList endpoint and base for JobDetail. See DESIGN.md section 9.1.
 *
 * `id` is the canonical UUID string (no java.util.UUID — KMP-safe). Worker code converts
 * to/from kotlin.uuid.Uuid as needed.
 */
@Serializable
public data class JobView(
    val id: String,
    val state: JobState,
    val queue: String,
    val priority: JobPriority,
    val payloadType: String,
    val scheduledAt: Instant?,
    val attempts: Int,
    val maxAttempts: Int,
    val lockedBy: String?,
    val progress: Float? = null,
    val progressMsg: String? = null,
    val durationMs: Long? = null,
    val createdAt: Instant,
    val updatedAt: Instant,
)
