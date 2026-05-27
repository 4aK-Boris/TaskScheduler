package cs.trade.scheduler.dashboard.web.domain.usecases

import cs.trade.scheduler.dashboard.web.domain.repositories.StatsRepository
import cs.trade.scheduler.shared.dto.StatsOverviewResponse

public class GetStatsOverviewUseCase(private val repo: StatsRepository) {
    public suspend operator fun invoke(): Result<StatsOverviewResponse> = runCatching { repo.overview() }
}
