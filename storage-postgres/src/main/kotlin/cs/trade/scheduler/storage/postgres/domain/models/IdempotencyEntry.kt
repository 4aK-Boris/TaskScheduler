@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package cs.trade.scheduler.storage.postgres.domain.models

import kotlin.time.Instant
import kotlin.uuid.Uuid

// One row in `idempotency_log` (V1 schema, DESIGN.md 17.3 / 18.4). Composite key
// (jobId, action) — one job can mark many actions in multi-step handlers.
public data class IdempotencyEntry(
    val jobId: Uuid,
    val action: String,
    val occurredAt: Instant,
)
