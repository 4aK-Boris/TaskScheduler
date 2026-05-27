@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package cs.trade.scheduler.storage.postgres.infrastructure.tables

import org.jetbrains.exposed.v1.core.Column
import org.jetbrains.exposed.v1.core.dao.id.LongIdTable
import org.jetbrains.exposed.v1.javatime.timestampWithTimeZone
import java.time.OffsetDateTime
import kotlin.uuid.Uuid

/**
 * Exposed mapping for `outbox`. BIGSERIAL PK → [LongIdTable].
 *
 * Exposed 1.x `uuid(...)` returns `Column<kotlin.uuid.Uuid>` (not java.util.UUID) — matches
 * our domain model's `kotlin.uuid.Uuid`. No bridge conversion needed at the repository layer.
 */
public object OutboxTable : LongIdTable("outbox") {
    public val jobId: Column<Uuid> = uuid("job_id")
    public val routingKey: Column<String> = text("routing_key")
    public val priority: Column<Int> = integer("priority").default(0)
    public val delayMs: Column<Long> = long("delay_ms").default(0)
    public val createdAt: Column<OffsetDateTime> = timestampWithTimeZone("created_at")
    public val publishedAt: Column<OffsetDateTime?> = timestampWithTimeZone("published_at").nullable()
}
