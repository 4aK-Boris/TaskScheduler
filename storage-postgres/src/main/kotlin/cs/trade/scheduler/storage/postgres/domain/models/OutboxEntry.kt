package cs.trade.scheduler.storage.postgres.domain.models

import kotlin.time.Instant
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * One row from the `outbox` table — a pending or published "publish this job to Rabbit"
 * intent. Created in the same transaction as the parent `job` row (DESIGN.md section 4.1).
 */
@OptIn(ExperimentalUuidApi::class)
public data class OutboxEntry(
    val id: Long,
    val jobId: Uuid,
    val routingKey: String,
    val priority: Int,
    val delayMs: Long,
    val createdAt: Instant,
    val publishedAt: Instant?,
)

/** Input shape for inserting a new outbox row (no id / createdAt / publishedAt). */
@OptIn(ExperimentalUuidApi::class)
public data class NewOutboxEntry(
    val jobId: Uuid,
    val routingKey: String,
    val priority: Int = 0,
    val delayMs: Long = 0,
)
