package cs.trade.scheduler.storage.postgres.infrastructure.tables

import org.jetbrains.exposed.v1.core.Column
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.javatime.timestampWithTimeZone
import java.time.OffsetDateTime

/**
 * Exposed mapping for `job_type_pause` (V1__initial_schema.sql).
 *
 * PK on `payload_type` — pausing is type-wide. See DESIGN.md 22.1.
 */
public object JobTypePauseTable : Table("job_type_pause") {
    public val payloadType: Column<String> = text("payload_type")
    public val pausedSince: Column<OffsetDateTime> = timestampWithTimeZone("paused_since")
    public val pausedBy: Column<String> = text("paused_by")
    public val reason: Column<String?> = text("reason").nullable()

    override val primaryKey: PrimaryKey = PrimaryKey(payloadType)
}
