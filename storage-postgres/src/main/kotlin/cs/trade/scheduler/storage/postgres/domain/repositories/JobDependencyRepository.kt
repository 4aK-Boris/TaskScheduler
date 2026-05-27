@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package cs.trade.scheduler.storage.postgres.domain.repositories

import cs.trade.scheduler.shared.OnFailure
import cs.trade.scheduler.storage.postgres.domain.models.JobDependency
import kotlin.uuid.Uuid

/**
 * Edges of the job DAG. See DESIGN.md 7.4 / 8.3.
 */
public interface JobDependencyRepository {

    /** Insert one edge. PK `(parent_id, child_id)`. */
    public suspend fun insert(parentId: Uuid, childId: Uuid, onFailure: OnFailure)

    /**
     * All children that wait on [parentId]. Used by `FinalizeJobUseCase` when the
     * parent reaches a terminal state to drive dep-counter resolution.
     */
    public suspend fun findChildrenOfParent(parentId: Uuid): List<JobDependency>

    /**
     * All parents the [childId] waits on. Used by the dashboard for DAG visualisation.
     */
    public suspend fun findParentsOfChild(childId: Uuid): List<JobDependency>
}
