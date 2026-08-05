package cs.trade.scheduler.storage.postgres.infrastructure.tables

import org.jetbrains.exposed.v1.core.Column
import org.jetbrains.exposed.v1.core.dao.id.UuidTable
import org.jetbrains.exposed.v1.javatime.timestampWithTimeZone
import java.time.OffsetDateTime

/**
 * Exposed table mapping for `job`. See V1__initial_schema.sql.
 *
 * Other tables (job_event, job_dependency, recurring_job, outbox, worker,
 * idempotency_log, job_type_pause) follow the same pattern in this directory.
 * One file per table — symmetric with the main project's `infrastructure/tables/` rule.
 *
 * Notes on Exposed 1.x:
 *  - All packages live under `org.jetbrains.exposed.v1.*` (major repackaging in 1.0).
 *  - [UuidTable] uses `kotlin.uuid.Uuid` for id type — matches our domain model
 *    (`Job.id: Uuid`). The legacy `UUIDTable` (java.util.UUID) lives in
 *    `org.jetbrains.exposed.v1.core.dao.id.java.UUIDTable` if interop with old code needed.
 *  - JSONB columns (`payload_json`, `context_json`) are stored as `text(...)` —
 *    PostgreSQL accepts text → jsonb on INSERT and returns jsonb as text on SELECT,
 *    so we keep the raw JSON string and let the worker layer (de)serialise.
 *    Switch to typed `jsonb(...)` from `exposed-json` when we want compile-time payload shapes.
 */
public object JobTable : UuidTable("job") {
    public val state: Column<String> = text("state")
    public val queue: Column<String> = text("queue")
    public val priority: Column<Int> = integer("priority").default(0)
    public val payloadType: Column<String> = text("payload_type")
    public val payloadJson: Column<String> = text("payload_json")

    public val scheduledAt: Column<OffsetDateTime?> = timestampWithTimeZone("scheduled_at").nullable()

    public val attempts: Column<Int> = integer("attempts").default(0)
    public val maxAttempts: Column<Int> = integer("max_attempts").default(3)
    public val timeoutSeconds: Column<Int?> = integer("timeout_seconds").nullable()

    public val lockedBy: Column<String?> = text("locked_by").nullable()
    public val lockedUntil: Column<OffsetDateTime?> = timestampWithTimeZone("locked_until").nullable()

    public val pendingDeps: Column<Int> = integer("pending_deps").default(0)
    public val initialPendingDeps: Column<Int> = integer("initial_pending_deps").default(0)
    public val version: Column<Int> = integer("version").default(0)

    public val idempotencyKey: Column<String?> = text("idempotency_key").nullable()
    public val targetNode: Column<String?> = text("target_node").nullable()
    public val targetTag: Column<String?> = text("target_tag").nullable()

    public val progress: Column<Float?> = float("progress").nullable()
    public val progressMsg: Column<String?> = text("progress_msg").nullable()
    public val progressUpdatedAt: Column<OffsetDateTime?> = timestampWithTimeZone("progress_updated_at").nullable()

    // Counting progress bar (JobContext.progressBar). NULL unless the handler used the
    // counting API — `progress` stays the derived fraction either way. See V6 migration.
    public val progressSucceeded: Column<Long?> = long("progress_succeeded").nullable()
    public val progressFailed: Column<Long?> = long("progress_failed").nullable()
    public val progressTotal: Column<Long?> = long("progress_total").nullable()

    public val startedAt: Column<OffsetDateTime?> = timestampWithTimeZone("started_at").nullable()
    public val durationMs: Column<Long?> = long("duration_ms").nullable()

    public val cancelRequestedAt: Column<OffsetDateTime?> = timestampWithTimeZone("cancel_requested_at").nullable()
    public val cancelRequestedBy: Column<String?> = text("cancel_requested_by").nullable()

    public val contextJson: Column<String?> = text("context_json").nullable()

    public val createdAt: Column<OffsetDateTime> = timestampWithTimeZone("created_at")
    public val updatedAt: Column<OffsetDateTime> = timestampWithTimeZone("updated_at")

    /** Recurring definition that fired this job; NULL for one-off enqueues. See V9 migration. */
    public val recurringId: Column<String?> = text("recurring_id").nullable()
}
