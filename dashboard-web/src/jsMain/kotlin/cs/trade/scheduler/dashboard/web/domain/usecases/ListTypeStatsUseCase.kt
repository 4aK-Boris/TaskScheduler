package cs.trade.scheduler.dashboard.web.domain.usecases

import cs.trade.scheduler.dashboard.web.domain.repositories.TypeStatsRepository
import cs.trade.scheduler.shared.dto.TypeStatsResponse

public class ListTypeStatsUseCase(private val repo: TypeStatsRepository) {
    public suspend operator fun invoke(rangeHours: Int): Result<TypeStatsResponse> =
        runCatching { repo.list(rangeHours) }
}
