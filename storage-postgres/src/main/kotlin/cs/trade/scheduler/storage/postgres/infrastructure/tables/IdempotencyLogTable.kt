@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package cs.trade.scheduler.storage.postgres.infrastructure.tables

import org.jetbrains.exposed.v1.core.Column
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.javatime.timestampWithTimeZone
import java.time.OffsetDateTime
import kotlin.uuid.Uuid

/**
 * Exposed mapping for `idempotency_log` (V1__initial_schema.sql).
 *
 * Composite PK `(job_id, action)` — one job can mark multiple actions in multi-step
 * handlers. No FK to `job` on purpose (DESIGN.md 18.4): retention TTL is independent
 * so external API idempotency keys can outlive the originating job row.
 */
public object IdempotencyLogTable : Table("idempotency_log") {
    public val jobId: Column<Uuid> = uuid("job_id")
    public val action: Column<String> = text("action")
    public val occurredAt: Column<OffsetDateTime> = timestampWithTimeZone("occurred_at")

    override val primaryKey: PrimaryKey = PrimaryKey(jobId, action)
}
