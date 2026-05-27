@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package cs.trade.scheduler.engine.worker.domain.usecases

import cs.trade.scheduler.core.backend.events.EventBus
import cs.trade.scheduler.core.backend.usecases.BaseUseCase
import cs.trade.scheduler.core.backend.usecases.runCatchingWithLogging
import cs.trade.scheduler.shared.events.WebSocketEvent
import cs.trade.scheduler.storage.postgres.domain.repositories.JobRepository
import cs.trade.scheduler.storage.postgres.domain.repositories.JobRollupRepository
import kotlin.time.Clock
import kotlin.uuid.Uuid

/**
 * Walks the rollup graph upward from a changed child and recomputes each ancestor's
 * aggregate `progress`. Hooked from:
 *
 *  - [ReportProgressUseCase] — after a handler reports child progress
 *  - [FinalizeJobUseCase] — after a child becomes terminal (its effective progress
 *    flips from `progress ?: 0` to 1.0, so parents need a refresh)
 *
 * BFS with a visited-set so cycles (which shouldn't exist but defence-in-depth) can't
 * hang the loop. Single-level rollups are the common case (one umbrella per fanout);
 * transitive rollups (umbrella-of-umbrellas) get the same treatment for free.
 *
 * Failures inside the BFS are logged-and-swallowed via [runCatchingWithLogging] — a
 * broken aggregation must NOT fail the calling finalize/setProgress path. The dashboard
 * tolerates a stale rollup value better than the cluster tolerates a finalize abort.
 */
public class PropagateRollupProgressUseCase(
    private val jobs: JobRepository,
    private val rollups: JobRollupRepository,
    private val eventBus: EventBus,
) : BaseUseCase() {

    public suspend operator fun invoke(changedChildId: Uuid): Result<Int> = runCatchingWithLogging {
        val visited = mutableSetOf<Uuid>()
        val queue = ArrayDeque<Uuid>().apply { add(changedChildId) }
        var updated = 0

        while (queue.isNotEmpty()) {
            val currentChild = queue.removeFirst()
            // Guard against cycles AND the trivial case of the changed child also being
            // its own rollup parent (impossible by schema but cheap to defend).
            if (!visited.add(currentChild)) continue

            val parents = rollups.findParentsOf(currentChild)
            if (parents.isEmpty()) continue

            val now = Clock.System.now()
            for (parentId in parents) {
                val agg = rollups.computeAggregateProgress(parentId) ?: continue
                val written = jobs.setRollupProgress(parentId, agg, now)
                if (written) {
                    updated++
                    eventBus.publish(
                        WebSocketEvent.JobProgress(
                            id = parentId.toString(),
                            progress = agg,
                            msg = null,
                            at = now,
                        ),
                    )
                }
                // Walk further up — the parent might itself be a rollup child.
                queue.addLast(parentId)
            }
        }
        updated
    }
}
