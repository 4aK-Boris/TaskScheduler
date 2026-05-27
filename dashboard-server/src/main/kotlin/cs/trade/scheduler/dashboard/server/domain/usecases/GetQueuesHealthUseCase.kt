package cs.trade.scheduler.dashboard.server.domain.usecases

import cs.trade.scheduler.core.backend.usecases.BaseUseCase
import cs.trade.scheduler.core.backend.usecases.runCatchingWithLogging
import cs.trade.scheduler.dashboard.server.QueueHealthThresholds
import cs.trade.scheduler.shared.dto.QueueHealthDto
import cs.trade.scheduler.shared.dto.QueueHealthStatus
import cs.trade.scheduler.storage.postgres.domain.repositories.JobRepository

/**
 * Snapshot of "how loaded is each queue right now?" (DESIGN.md 20.10). Drives the
 * dashboard backpressure indicator — a yellow / red badge near the queue filter on
 * JobList so operators can see at a glance that a queue is filling faster than it
 * drains.
 *
 * Reads non-terminal depth per queue from [JobRepository.countActiveByQueue], applies
 * the configured [QueueHealthThresholds] to bucket each queue into NORMAL / ELEVATED /
 * OVERLOADED, returns the rows sorted by depth-descending so the UI's natural ordering
 * surfaces the worst-loaded queues first.
 *
 * Empty result is normal — no non-terminal rows means no load to report. The badge
 * component renders nothing in that case.
 */
public class GetQueuesHealthUseCase(
    private val jobs: JobRepository,
    private val thresholds: QueueHealthThresholds,
) : BaseUseCase() {

    public suspend operator fun invoke(): Result<List<QueueHealthDto>> = runCatchingWithLogging {
        val depthByQueue = jobs.countActiveByQueue()
        depthByQueue
            .map { (queue, depth) ->
                QueueHealthDto(
                    queue = queue,
                    depth = depth,
                    status = statusFor(depth),
                )
            }
            .sortedByDescending { it.depth }
    }

    private fun statusFor(depth: Long): QueueHealthStatus = when {
        depth >= thresholds.overloaded -> QueueHealthStatus.OVERLOADED
        depth >= thresholds.elevated -> QueueHealthStatus.ELEVATED
        else -> QueueHealthStatus.NORMAL
    }
}
