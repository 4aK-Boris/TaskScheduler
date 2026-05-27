@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package cs.trade.scheduler.storage.postgres.infrastructure.tables

import org.jetbrains.exposed.v1.core.Column
import org.jetbrains.exposed.v1.core.dao.id.LongIdTable
import org.jetbrains.exposed.v1.javatime.timestampWithTimeZone
import java.time.OffsetDateTime
import kotlin.uuid.Uuid

// Exposed mapping for `job_event` (V1__initial_schema.sql).
// id is BIGSERIAL — LongIdTable maps that. job_id has ON DELETE CASCADE, so deleting
// the parent job nukes its timeline automatically.
public object JobEventTable : LongIdTable("job_event", "id") {
    public val jobId: Column<Uuid> = uuid("job_id")
    public val eventType: Column<String> = text("event_type")
    public val prevState: Column<String?> = text("prev_state").nullable()
    public val newState: Column<String?> = text("new_state").nullable()
    public val actor: Column<String?> = text("actor").nullable()
    public val errorMsg: Column<String?> = text("error_msg").nullable()
    public val errorStack: Column<String?> = text("error_stack").nullable()
    public val occurredAt: Column<OffsetDateTime> = timestampWithTimeZone("occurred_at")
}
