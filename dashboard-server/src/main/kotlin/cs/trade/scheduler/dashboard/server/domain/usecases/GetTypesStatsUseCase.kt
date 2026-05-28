package cs.trade.scheduler.dashboard.server.domain.usecases

import cs.trade.scheduler.core.backend.usecases.BaseUseCase
import cs.trade.scheduler.core.backend.usecases.runCatchingWithLogging
import cs.trade.scheduler.shared.dto.TypeStatsDto
import cs.trade.scheduler.storage.postgres.domain.repositories.JobRepository
import org.koin.core.annotation.Single

/**
 * Aggregate per-payload-type stats for the dashboard "Type Stats" page (DESIGN.md 22.4).
 *
 * `rangeHours` is bounded to [1..720] — anything longer would cost a full sequential
 * scan over the `job` table and isn't useful for operator-facing dashboards (use the
 * archive sink + a real OLAP system for longer horizons).
 */
@Single
public class GetTypesStatsUseCase(
    private val jobs: JobRepository,
) : BaseUseCase() {

    public suspend operator fun invoke(rangeHours: Int): Result<List<TypeStatsDto>> = runCatchingWithLogging {
        require(rangeHours in 1..MAX_HOURS) { "rangeHours must be in [1, $MAX_HOURS] (got $rangeHours)" }
        jobs.statsByPayloadType(rangeHours).map { row ->
            TypeStatsDto(
                payloadType = row.payloadType,
                queue = row.queue,
                successCount = row.successCount,
                failedCount = row.failedCount,
                cancelledCount = row.cancelledCount,
                retryCount = row.retryCount,
                avgDurationMs = row.avgDurationMs,
                minDurationMs = row.minDurationMs,
                maxDurationMs = row.maxDurationMs,
                p95DurationMs = row.p95DurationMs,
            )
        }
    }

    private companion object {
        // 720h = 30 days — same cap the StatsRouting `range` query param honours.
        const val MAX_HOURS = 720
    }
}
