package cs.trade.scheduler.storage.postgres.infrastructure.tables

import org.jetbrains.exposed.v1.core.Column
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.javatime.timestampWithTimeZone
import java.time.OffsetDateTime

// Exposed mapping for the `worker` registry (V1__initial_schema.sql).
//
// `tags` is `TEXT[]` in Postgres. Exposed core has `.array("name", ColumnType)` but
// we keep things simple: store as a single TEXT column with comma-separated values
// since `array<TEXT>` mapping needs an extra dependency configuration. Operational
// rows are small (<100 chars) and the dashboard reads them rarely.
//
// V1 schema defines `tags TEXT[]`, so the Postgres column type is array. Reading it
// as TEXT in Exposed will return the literal array representation like "{prod,gpu}".
// We mitigate this in the impl by parsing/serialising at the repo boundary.
public object WorkerTable : Table("worker") {
    public val nodeId: Column<String> = text("node_id")
    public val host: Column<String> = text("host")

    // PostgreSQL `text[]` is mapped as a raw String to dodge per-driver array support.
    // Repository serialises List<String> <-> "{a,b}" — matches PG's text array literal.
    public val tags: Column<String> = text("tags")

    public val lastHeartbeat: Column<OffsetDateTime> = timestampWithTimeZone("last_heartbeat")
    public val startedAt: Column<OffsetDateTime> = timestampWithTimeZone("started_at")
    public val inFlightCount: Column<Int> = integer("in_flight_count").default(0)

    // JSONB in Postgres, stored as text() in Exposed — same trick as JobTable.contextJson.
    // PgJDBC's `stringtype=unspecified` (set on the HikariDataSource) lets Postgres parse
    // the literal JSON on INSERT. Repo serialises Map<String,Int> via kotlinx.serialization.
    public val inFlightByQueue: Column<String> = text("in_flight_by_queue").default("{}")

    override val primaryKey: PrimaryKey = PrimaryKey(nodeId)
}
