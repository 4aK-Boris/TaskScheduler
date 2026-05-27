@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package cs.trade.scheduler.storage.postgres.domain.repositories

import kotlin.uuid.Uuid

/**
 * Edges where a parent observes the aggregate progress of a set of children. Independent
 * of [JobDependencyRepository] (which gates execution). A job can be both a blocker AND
 * a rollup target for the same children; both relationships coexist in their own tables.
 *
 * See DESIGN.md "DAG progress propagation — variant 3".
 */
public interface JobRollupRepository {

    /**
     * Register a `parentId watches childId` edge. Idempotent — duplicate inserts on the
     * composite PK are silently ignored (returns whether the row was new).
     */
    public suspend fun attach(parentId: Uuid, childId: Uuid): Boolean

    /**
     * All parents that roll up [childId]. Used by the propagation hook (after a child's
     * progress / terminal change) to walk upward and recompute each parent's aggregate.
     * Bounded by [limit] — in practice rollup parents are typically 0 or 1 per child.
     */
    public suspend fun findParentsOf(childId: Uuid, limit: Int = 100): List<Uuid>

    /**
     * Aggregate child progress for [parentId] as a single SQL pass:
     *
     *     SELECT AVG(CASE
     *         WHEN state IN ('SUCCEEDED','FAILED','CANCELLED') THEN 1.0
     *         ELSE COALESCE(progress, 0)
     *     END)
     *
     * Returns `null` if [parentId] has no rollup children at all — caller should leave
     * the parent's progress alone in that case (don't overwrite handler-reported values).
     */
    public suspend fun computeAggregateProgress(parentId: Uuid): Float?
}
