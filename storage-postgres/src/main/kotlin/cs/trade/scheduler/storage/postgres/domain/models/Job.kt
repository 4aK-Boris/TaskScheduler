package cs.trade.scheduler.storage.postgres.domain.models

import cs.trade.scheduler.shared.JobPriority
import cs.trade.scheduler.shared.JobState
import kotlin.time.Instant
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * Domain model for a `job` row. Pure data class — no serialization annotations, those
 * live on DTOs in `:core:shared/dto/`.
 *
 * Mirrors DESIGN.md section 6.
 */
@OptIn(ExperimentalUuidApi::class)
public data class Job(
    val id: Uuid,
    val state: JobState,
    val queue: String,
    val priority: JobPriority,
    val payloadType: String,
    val payloadJson: String,                        // raw JSON, deserialised by worker at execute time
    val scheduledAt: Instant?,
    val attempts: Int,
    val maxAttempts: Int,
    val timeoutSeconds: Int?,
    val lockedBy: String?,
    val lockedUntil: Instant?,
    val pendingDeps: Int,
    /**
     * Snapshot of `pendingDeps` at insert time — never mutated. Used as the
     * denominator for dependency-progress (variant 1): a child's `progress` is
     * derived as `(initialPendingDeps - pendingDeps) / initialPendingDeps` while
     * it sits in AWAITING_DEPS. Defaults to `0` so older call-sites without DAG
     * deps don't need to set it explicitly.
     */
    val initialPendingDeps: Int = 0,
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
    val contextJson: String?,                       // raw JSON {mdc, traceparent, tracestate}
    val createdAt: Instant,
    val updatedAt: Instant,
    /**
     * Counting-progress-bar metadata ([cs.trade.scheduler.core.backend.handler.JobContext.progressBar]).
     * `null` when the handler used plain `updateProgress` (or never reported). `progress`
     * remains the derived fraction `(succeeded + failed) / total`. Defaults so existing
     * positional call-sites don't need to change.
     */
    val progressSucceeded: Long? = null,
    val progressFailed: Long? = null,
    val progressTotal: Long? = null,
)
