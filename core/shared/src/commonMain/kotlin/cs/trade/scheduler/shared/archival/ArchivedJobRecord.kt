package cs.trade.scheduler.shared.archival

import cs.trade.scheduler.shared.JobState
import kotlin.time.Instant
import kotlinx.serialization.Serializable

/**
 * Wire-stable archive format for a terminal `job` row (DESIGN.md 18.7). Snapshotted by
 * [cs.trade.scheduler.core.backend.archival.ArchivalSink] before the retention loop
 * issues `DELETE FROM job` so forensic / compliance use-cases can read the row years
 * later from cold storage without the live DB.
 *
 * Only the `job` row is archived — `job_event`, `job_dependency`, `outbox` cascade-delete
 * with their parent and are NOT mirrored here. Apps that need full audit lineage can
 * register their own sink to also archive those (separate hooks aren't built in yet).
 *
 * Field shape matches `storage-postgres/.../models/Job.kt` 1:1 but with primitive types
 * only (no `JobPriority` value wrapper) so the JSON stays portable for non-Kotlin readers.
 */
@Serializable
public data class ArchivedJobRecord(
    val id: String,
    val state: JobState,
    val queue: String,
    val priority: Int,
    val payloadType: String,
    val payloadJson: String,
    val scheduledAt: Instant?,
    val attempts: Int,
    val maxAttempts: Int,
    val timeoutSeconds: Int?,
    val lockedBy: String?,
    val lockedUntil: Instant?,
    val pendingDeps: Int,
    val initialPendingDeps: Int,
    val version: Int,
    val idempotencyKey: String?,
    val targetNode: String?,
    val targetTag: String?,
    val progress: Float?,
    val progressMsg: String?,
    val progressUpdatedAt: Instant?,
    val startedAt: Instant?,
    val durationMs: Long?,
    val cancelRequestedAt: Instant?,
    val cancelRequestedBy: String?,
    val contextJson: String?,
    val createdAt: Instant,
    val updatedAt: Instant,
)
