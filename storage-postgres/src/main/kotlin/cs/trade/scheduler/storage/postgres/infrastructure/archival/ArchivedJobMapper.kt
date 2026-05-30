@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package cs.trade.scheduler.storage.postgres.infrastructure.archival

import cs.trade.scheduler.shared.archival.ArchivedJobRecord
import cs.trade.scheduler.storage.postgres.domain.models.Job

/** Domain → wire DTO. JobPriority unwrapped to its int value for non-Kotlin readers. */
public fun Job.toArchivedRecord(): ArchivedJobRecord = ArchivedJobRecord(
    id = id.toString(),
    state = state,
    queue = queue,
    priority = priority.value,
    payloadType = payloadType,
    payloadJson = payloadJson,
    scheduledAt = scheduledAt,
    attempts = attempts,
    maxAttempts = maxAttempts,
    timeoutSeconds = timeoutSeconds,
    lockedBy = lockedBy,
    lockedUntil = lockedUntil,
    pendingDeps = pendingDeps,
    initialPendingDeps = initialPendingDeps,
    version = version,
    idempotencyKey = idempotencyKey,
    targetNode = targetNode,
    targetTag = targetTag,
    progress = progress,
    progressMsg = progressMsg,
    progressUpdatedAt = progressUpdatedAt,
    startedAt = startedAt,
    durationMs = durationMs,
    cancelRequestedAt = cancelRequestedAt,
    cancelRequestedBy = cancelRequestedBy,
    contextJson = contextJson,
    createdAt = createdAt,
    updatedAt = updatedAt,
    progressSucceeded = progressSucceeded,
    progressFailed = progressFailed,
    progressTotal = progressTotal,
)
