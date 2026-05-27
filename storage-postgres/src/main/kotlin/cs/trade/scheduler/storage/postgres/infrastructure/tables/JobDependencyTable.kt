@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package cs.trade.scheduler.storage.postgres.infrastructure.tables

import org.jetbrains.exposed.v1.core.Column
import org.jetbrains.exposed.v1.core.Table
import kotlin.uuid.Uuid

/**
 * Exposed mapping for `job_dependency` (V1__initial_schema.sql section "job_dependency").
 *
 * Composite PK `(parent_id, child_id)`. Both columns FK to `job(id)` ON DELETE CASCADE
 * — DDL handled by Flyway, not by Exposed.
 */
public object JobDependencyTable : Table("job_dependency") {
    public val parentId: Column<Uuid> = uuid("parent_id")
    public val childId: Column<Uuid> = uuid("child_id")
    public val onFailure: Column<String> = text("on_failure")

    override val primaryKey: PrimaryKey = PrimaryKey(parentId, childId)
}
