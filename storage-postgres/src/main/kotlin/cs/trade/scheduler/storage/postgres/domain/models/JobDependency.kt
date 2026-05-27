@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package cs.trade.scheduler.storage.postgres.domain.models

import cs.trade.scheduler.shared.OnFailure
import kotlin.uuid.Uuid

/**
 * One edge in the job DAG: `parentId` must reach a terminal state before `childId`'s
 * `pending_deps` counter ticks down. See DESIGN.md sections 7.4 and 8.3.
 *
 * `onFailure` controls what happens to the child when the parent ends in FAILED or
 * CANCELLED — see [cs.trade.scheduler.shared.OnFailure].
 */
public data class JobDependency(
    val parentId: Uuid,
    val childId: Uuid,
    val onFailure: OnFailure,
)
