package cs.trade.scheduler.dashboard.server.domain.usecases

import cs.trade.scheduler.core.backend.usecases.BaseUseCase
import cs.trade.scheduler.core.backend.usecases.runCatchingWithLogging
import cs.trade.scheduler.shared.JobState
import cs.trade.scheduler.storage.postgres.domain.repositories.JobRepository
import org.koin.core.annotation.Single

// MVP stats: just per-state counters from the job table. Queue snapshots + worker
// summary stubbed pending the WorkerRepository impl + per-queue depth tracking.
@Single
public class GetStatsOverviewUseCase(
    private val jobs: JobRepository,
) : BaseUseCase() {

    public suspend operator fun invoke(): Result<Counters> = runCatchingWithLogging {
        val byState = jobs.countByState()
        Counters(
            enqueued = byState.getOrDefault(JobState.ENQUEUED, 0L),
            processing = byState.getOrDefault(JobState.PROCESSING, 0L),
            awaitingRetry = byState.getOrDefault(JobState.AWAITING_RETRY, 0L),
            awaitingDeps = byState.getOrDefault(JobState.AWAITING_DEPS, 0L),
            scheduled = byState.getOrDefault(JobState.SCHEDULED, 0L),
            succeeded = byState.getOrDefault(JobState.SUCCEEDED, 0L),
            failed = byState.getOrDefault(JobState.FAILED, 0L),
            cancelled = byState.getOrDefault(JobState.CANCELLED, 0L),
        )
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
