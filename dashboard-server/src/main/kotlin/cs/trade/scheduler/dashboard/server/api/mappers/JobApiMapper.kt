@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package cs.trade.scheduler.dashboard.server.api.mappers

import cs.trade.scheduler.dashboard.server.api.dto.ListJobsQuery
import cs.trade.scheduler.shared.JobState
import cs.trade.scheduler.shared.dto.JobDetail
import cs.trade.scheduler.shared.dto.JobEventDto
import cs.trade.scheduler.shared.dto.JobGraph
import cs.trade.scheduler.shared.dto.JobGraphEdge
import cs.trade.scheduler.shared.dto.JobView
import cs.trade.scheduler.storage.postgres.domain.models.Job
import cs.trade.scheduler.storage.postgres.domain.models.JobDependency
import cs.trade.scheduler.storage.postgres.domain.models.JobEventRow
import cs.trade.scheduler.storage.postgres.domain.models.JobListFilter
import org.koin.core.annotation.Single
import kotlin.uuid.Uuid

// Domain <-> API DTO converters for /api/jobs. Pure type conversion, no I/O.
@Single
public class JobApiMapper {

    public fun toJobId(raw: String): Uuid = Uuid.parse(raw)

    // Safe to call only on a validated [ListJobsQuery] — `JobState.valueOf` would throw
    // on a bad name otherwise. JobValidation.validateListJobsQuery's `enum<JobState>()`
    // constraint is the gate.
    public fun toJobListFilter(query: ListJobsQuery): JobListFilter = JobListFilter(
        states = query.states.takeIf { it.isNotEmpty() }?.mapTo(mutableSetOf()) { JobState.valueOf(it) },
        queue = query.queue,
        payloadType = query.payloadType,
        attemptsExhausted = query.attemptsExhausted,
    )

    public fun toView(job: Job): JobView = JobView(
        id = job.id.toString(),
        state = job.state,
        queue = job.queue,
        priority = job.priority,
        payloadType = job.payloadType,
        scheduledAt = job.scheduledAt,
        attempts = job.attempts,
        maxAttempts = job.maxAttempts,
        lockedBy = job.lockedBy,
        progress = job.progress,
        progressMsg = job.progressMsg,
        durationMs = job.durationMs,
        createdAt = job.createdAt,
        updatedAt = job.updatedAt,
    )

    public fun toEventDto(event: JobEventRow): JobEventDto = JobEventDto(
        id = event.id,
        jobId = event.jobId.toString(),
        eventType = event.eventType,
        prevState = event.prevState,
        newState = event.newState,
        actor = event.actor,
        errorMsg = event.errorMsg,
        errorStack = event.errorStack,
        occurredAt = event.occurredAt,
    )

    // Full detail. The dependency graph is the transitive DAG component supplied by
    // GetJobDetailUseCase (BFS over JobDependencyRepository edges + findById hydration).
    public fun toDetail(
        job: Job,
        events: List<JobEventRow>,
        graphNodes: List<Job>,
        graphEdges: List<JobDependency>,
        graphTruncated: Boolean,
    ): JobDetail = JobDetail(
        job = toView(job),
        payloadJson = job.payloadJson,
        events = events.map(::toEventDto),
        graph = JobGraph(
            nodes = graphNodes.map(::toView),
            edges = graphEdges.map {
                JobGraphEdge(it.parentId.toString(), it.childId.toString(), it.onFailure)
            },
            truncated = graphTruncated,
        ),
    )
}
