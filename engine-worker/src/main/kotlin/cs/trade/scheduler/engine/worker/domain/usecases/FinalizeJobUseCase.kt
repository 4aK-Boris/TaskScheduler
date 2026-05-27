@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package cs.trade.scheduler.engine.worker.domain.usecases

import cs.trade.scheduler.core.backend.usecases.BaseUseCase
import cs.trade.scheduler.core.backend.usecases.runCatchingWithLogging
import cs.trade.scheduler.shared.JobState
import cs.trade.scheduler.shared.OnFailure
import cs.trade.scheduler.storage.postgres.domain.models.Job
import cs.trade.scheduler.storage.postgres.domain.models.NewOutboxEntry
import cs.trade.scheduler.storage.postgres.domain.repositories.JobDependencyRepository
import cs.trade.scheduler.storage.postgres.domain.repositories.JobRepository
import cs.trade.scheduler.storage.postgres.domain.repositories.OutboxRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.transactions.suspendTransaction
import kotlin.uuid.Uuid

/**
 * Terminal transition + DAG dep resolution in one transaction:
 *
 *   1. `mark*` the parent in [terminalState] (SUCCEEDED / FAILED / CANCELLED) with CAS.
 *   2. BFS through `findChildrenOfParent` and apply each child's `OnFailure` rule:
 *      - parent SUCCEEDED, or [OnFailure.IGNORE] on a failed parent → `decrementPendingDeps`;
 *        if the counter hits 0, also INSERT an outbox row (the child is now ENQUEUED).
 *      - failed parent + [OnFailure.PROPAGATE_FAILURE] → `cascadeTerminalIfAwaiting(FAILED)`
 *        and recurse into the child's own children.
 *      - failed parent + [OnFailure.CANCEL_CHILD] → `cascadeTerminalIfAwaiting(CANCELLED)`
 *        and recurse.
 *
 * Recursion is iterative (BFS queue) to avoid stack overflows on deep DAGs.
 *
 * **Returns:** `false` if the initial mark CAS-conflicted (another writer beat us — caller
 * just logs and moves on); `true` if the row was finalised (children may or may not have
 * been promoted depending on the DAG).
 *
 * **Atomicity:** the whole thing runs in one DB transaction. Wide DAGs can lock many
 * rows briefly; Phase 2 will move to an event-sourced dep resolver if that becomes a
 * problem (DESIGN.md 7.4).
 */
public class FinalizeJobUseCase(
    private val database: Database,
    private val jobs: JobRepository,
    private val outbox: OutboxRepository,
    private val deps: JobDependencyRepository,
    private val propagateRollup: PropagateRollupProgressUseCase,
) : BaseUseCase() {

    public suspend operator fun invoke(
        jobId: Uuid,
        expectedVersion: Int,
        terminalState: JobState,
        errorMsg: String? = null,
        errorStack: String? = null,
        actor: String? = null,
    ): Result<Boolean> = runCatchingWithLogging {
        require(terminalState.isTerminal) { "FinalizeJobUseCase requires a terminal state, got $terminalState" }
        val finalized = withContext(Dispatchers.IO) {
            suspendTransaction(db = database) {
                val marked = when (terminalState) {
                    JobState.SUCCEEDED -> jobs.markSucceeded(jobId, expectedVersion)
                    JobState.FAILED -> jobs.markFailed(jobId, expectedVersion, errorMsg, errorStack)
                    JobState.CANCELLED -> jobs.markCancelled(jobId, expectedVersion, errorMsg, actor)
                    else -> error("unreachable")
                }
                if (!marked) return@suspendTransaction false

                resolveCascade(rootParent = jobId, rootTerminal = terminalState)
                true
            }
        }
        // Variant 3 propagation runs AFTER the dep cascade transaction commits — rollup
        // aggregation reads the updated child state, then writes parents in its own
        // tx. Keeping it outside the cascade tx avoids extending the lock scope on
        // wide DAGs; the trade-off is a small window where the dashboard sees the
        // terminal child but not yet the bumped parent rollup. Acceptable for an
        // observability number.
        if (finalized) {
            propagateRollup(jobId)
        }
        finalized
    }

    private suspend fun resolveCascade(rootParent: Uuid, rootTerminal: JobState) {
        // BFS over the dep graph. Each entry: (parentJustFinalised, itsTerminalState).
        val queue = ArrayDeque<Pair<Uuid, JobState>>()
        queue.add(rootParent to rootTerminal)

        while (queue.isNotEmpty()) {
            val (parentId, parentState) = queue.removeFirst()
            val children = deps.findChildrenOfParent(parentId)
            for (dep in children) {
                if (parentState == JobState.SUCCEEDED || dep.onFailure == OnFailure.IGNORE) {
                    decrementAndMaybePromote(dep.childId)
                } else {
                    val cascadeTerminal = when (dep.onFailure) {
                        OnFailure.PROPAGATE_FAILURE -> JobState.FAILED
                        OnFailure.CANCEL_CHILD -> JobState.CANCELLED
                        OnFailure.IGNORE -> error("unreachable — handled above")
                    }
                    val cascaded = jobs.cascadeTerminalIfAwaiting(dep.childId, cascadeTerminal)
                    if (cascaded) {
                        queue.add(dep.childId to cascadeTerminal)
                    }
                }
            }
        }
    }

    private suspend fun decrementAndMaybePromote(childId: Uuid) {
        when (jobs.decrementPendingDeps(childId)) {
            JobRepository.DepDecrementResult.PROMOTED -> {
                // Child is now ENQUEUED — give it an outbox row so the publisher loop
                // dispatches it. Re-read for routing info (queue / target_node / target_tag).
                val child = jobs.findById(childId)
                if (child != null) {
                    outbox.insert(
                        NewOutboxEntry(
                            jobId = child.id,
                            routingKey = routingKeyFor(child),
                            priority = child.priority.value,
                            delayMs = 0,
                        ),
                    )
                }
            }
            JobRepository.DepDecrementResult.DECREMENTED,
            JobRepository.DepDecrementResult.NOT_AWAITING -> {
                // No outbox needed — counter just ticked OR row already moved on.
            }
        }
    }

    private fun routingKeyFor(job: Job): String = when {
        job.targetNode != null -> "node.${job.targetNode}"
        job.targetTag != null -> "tag.${job.targetTag}"
        else -> job.queue
    }
}
