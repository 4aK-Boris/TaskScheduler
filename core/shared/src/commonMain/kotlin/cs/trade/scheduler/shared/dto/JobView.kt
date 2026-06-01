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
    // Counting-progress-bar metadata (JobContext.progressBar). Null when the handler used
    // plain updateProgress (or didn't report at all) — the UI falls back to a single bar.
    val progressSucceeded: Long? = null,
    val progressFailed: Long? = null,
    val progressTotal: Long? = null,
    val durationMs: Long? = null,
    /** When a worker began executing the job (first PROCESSING transition). Null until it starts. */
    val startedAt: Instant? = null,
    val createdAt: Instant,
    val updatedAt: Instant,
)
