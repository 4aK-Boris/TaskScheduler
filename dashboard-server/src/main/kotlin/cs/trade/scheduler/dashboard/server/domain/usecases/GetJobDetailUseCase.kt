@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package cs.trade.scheduler.dashboard.server.domain.usecases

import cs.trade.scheduler.core.backend.usecases.BaseUseCase
import cs.trade.scheduler.core.backend.usecases.runCatchingWithLogging
import cs.trade.scheduler.storage.postgres.domain.models.Job
import cs.trade.scheduler.storage.postgres.domain.models.JobEventRow
import cs.trade.scheduler.storage.postgres.domain.repositories.JobDependencyRepository
import cs.trade.scheduler.storage.postgres.domain.repositories.JobEventRepository
import cs.trade.scheduler.storage.postgres.domain.repositories.JobRepository
import org.koin.core.annotation.Single
import kotlin.uuid.Uuid

// Loads everything the JobDetail screen needs in one UseCase: the row itself, its event
// timeline, and direct DAG neighbours (parents + children). No transaction wraps the
// reads — each piece may be one tick stale relative to the others, which the dashboard
// tolerates (it polls / receives WS invalidations and refetches).
//
// N+1 round trips for neighbours: 1 SELECT for the edge rows, then 1 findById per
// neighbour. Acceptable for the typical DAGs we expect (chain of 2-5 jobs, fan-in of
// a handful). If wide DAGs become common, a JOIN-based batch fetch lands here.
@Single
public class GetJobDetailUseCase(
    private val jobs: JobRepository,
    private val events: JobEventRepository,
    private val deps: JobDependencyRepository,
) : BaseUseCase() {

    public suspend operator fun invoke(jobId: Uuid): Result<JobWithEvents?> = runCatchingWithLogging {
        val job = jobs.findById(jobId) ?: return@runCatchingWithLogging null

        val eventsList = events.findByJobId(jobId)

        val parentEdges = deps.findParentsOfChild(jobId)
        val childEdges = deps.findChildrenOfParent(jobId)
        val parents = parentEdges.mapNotNull { jobs.findById(it.parentId) }
        val children = childEdges.mapNotNull { jobs.findById(it.childId) }

        JobWithEvents(job = job, events = eventsList, parents = parents, children = children)
    }

    public data class JobWithEvents(
        val job: Job,
        val events: List<JobEventRow>,
        val parents: List<Job>,
        val children: List<Job>,
    )
}
