@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package cs.trade.scheduler.storage.postgres.infrastructure.tables

import org.jetbrains.exposed.v1.core.Column
import org.jetbrains.exposed.v1.core.Table
import kotlin.uuid.Uuid

/**
 * Exposed mapping for `job_rollup` (V4__job_rollup.sql).
 *
 * Composite PK `(parent_id, child_id)`. Both columns FK to `job(id)` ON DELETE CASCADE.
 *
 * Stores "parent watches child's progress" edges — observational, NOT blocking. See
 * `JobDependencyTable` for the blocking edge variant.
 */
public object JobRollupTable : Table("job_rollup") {
    public val parentId: Column<Uuid> = uuid("parent_id")
    public val childId: Column<Uuid> = uuid("child_id")

    override val primaryKey: PrimaryKey = PrimaryKey(parentId, childId)
}
