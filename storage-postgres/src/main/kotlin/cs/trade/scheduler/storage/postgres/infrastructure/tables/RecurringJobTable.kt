package cs.trade.scheduler.storage.postgres.infrastructure.tables

import org.jetbrains.exposed.v1.core.Column
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.javatime.timestampWithTimeZone
import java.time.OffsetDateTime

/**
 * Exposed mapping for `recurring_job` (V1__initial_schema.sql).
 *
 * `id` is `TEXT PRIMARY KEY` — user-supplied identifier. We use a custom PrimaryKey
 * override instead of `IdTable` because we want String, not auto-generated Long/UUID.
 */
public object RecurringJobTable : Table("recurring_job") {
    public val id: Column<String> = text("id")
    public val cron: Column<String> = text("cron")
    public val timezone: Column<String?> = text("timezone").nullable()
    public val misfirePolicy: Column<String> = text("misfire_policy")
    public val queue: Column<String> = text("queue")
    public val priority: Column<Int> = integer("priority").default(0)
    public val targetNode: Column<String?> = text("target_node").nullable()
    public val targetTag: Column<String?> = text("target_tag").nullable()
    public val payloadType: Column<String> = text("payload_type")
    public val payloadJson: Column<String> = text("payload_json")
    public val lastTriggeredAt: Column<OffsetDateTime?> = timestampWithTimeZone("last_triggered_at").nullable()
    public val nextTriggerAt: Column<OffsetDateTime> = timestampWithTimeZone("next_trigger_at")
    public val enabled: Column<Boolean> = bool("enabled").default(true)

    override val primaryKey: PrimaryKey = PrimaryKey(id)
}
