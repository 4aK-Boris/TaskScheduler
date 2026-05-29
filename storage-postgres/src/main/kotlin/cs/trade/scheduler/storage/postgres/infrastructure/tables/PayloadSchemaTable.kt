package cs.trade.scheduler.storage.postgres.infrastructure.tables

import org.jetbrains.exposed.v1.core.Column
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.javatime.timestampWithTimeZone
import java.time.OffsetDateTime

/**
 * Exposed mapping for `payload_schema` (V5__payload_schema.sql) — the last-seen
 * serialization-schema hash per payload type, for schema-drift detection (DESIGN.md 22.9).
 */
public object PayloadSchemaTable : Table("payload_schema") {
    public val payloadType: Column<String> = text("payload_type")
    public val schemaHash: Column<String> = text("schema_hash")
    public val firstSeenAt: Column<OffsetDateTime> = timestampWithTimeZone("first_seen_at")
    public val updatedAt: Column<OffsetDateTime> = timestampWithTimeZone("updated_at")

    override val primaryKey: PrimaryKey = PrimaryKey(payloadType)
}
