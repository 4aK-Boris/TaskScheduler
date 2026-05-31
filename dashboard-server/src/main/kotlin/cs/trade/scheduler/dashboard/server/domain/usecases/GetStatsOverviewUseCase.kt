package cs.trade.scheduler.dashboard.server.domain.usecases

import cs.trade.scheduler.core.backend.usecases.BaseUseCase
import cs.trade.scheduler.core.backend.usecases.runCatchingWithLogging
import cs.trade.scheduler.shared.JobState
import cs.trade.scheduler.storage.postgres.domain.repositories.JobRepository
import org.koin.core.annotation.Single

// Stats overview: live (non-terminal) states are current counts; terminal outcomes (succeeded /
// failed / cancelled) are windowed to the requested range so the dashboard's range selector is
// meaningful. Queue snapshots + worker summary stubbed pending per-queue depth tracking.
@Single
public class GetStatsOverviewUseCase(
    private val jobs: JobRepository,
) : BaseUseCase() {

    public suspend operator fun invoke(rangeHours: Int): Result<Counters> = runCatchingWithLogging {
        require(rangeHours in 1..MAX_HOURS) { "rangeHours must be in [1, $MAX_HOURS] (got $rangeHours)" }
        // Live states reflect "right now"; a time window is meaningless for them. Only the terminal
        // outcomes are counted within the trailing window.
        val byState = jobs.countByState()
        val terminal = jobs.countTerminalByStateSince(rangeHours)
        Counters(
            enqueued = byState.getOrDefault(JobState.ENQUEUED, 0L),
            processing = byState.getOrDefault(JobState.PROCESSING, 0L),
            awaitingRetry = byState.getOrDefault(JobState.AWAITING_RETRY, 0L),
            awaitingDeps = byState.getOrDefault(JobState.AWAITING_DEPS, 0L),
            scheduled = byState.getOrDefault(JobState.SCHEDULED, 0L),
            succeeded = terminal.getOrDefault(JobState.SUCCEEDED, 0L),
            failed = terminal.getOrDefault(JobState.FAILED, 0L),
            cancelled = terminal.getOrDefault(JobState.CANCELLED, 0L),
        )
    }

    private companion object {
        // 720h = 30 days — same cap as GetTypesStatsUseCase.
        const val MAX_HOURS = 24 * 30
    }

    public data class Counters(
        val enqueued: Long,
        val processing: Long,
        val awaitingRetry: Long,
        val awaitingDeps: Long,
        val scheduled: Long,
        val succeeded: Long,
        val failed: Long,
        val cancelled: Long,
    )
}
