package cs.trade.scheduler.dashboard.web.domain.usecases

import cs.trade.scheduler.dashboard.web.domain.repositories.JobsRepository
import cs.trade.scheduler.shared.JobState
import cs.trade.scheduler.shared.dto.ListJobsResponse

/**
 * Wraps [JobsRepository.list] per the "1 function repo ↔ 1 UseCase" rule applied
 * also to the frontend (DESIGN.md 3.3 — symmetric to `:dashboard-server`).
 *
 * Returns the raw [ListJobsResponse] — pagination metadata + items together — so the
 * component can show "Showing N of total".
 */
public class GetJobsListUseCase(
    private val repository: JobsRepository,
) {
    public suspend operator fun invoke(
        states: Set<JobState> = emptySet(),
        queue: String? = null,
        payloadType: String? = null,
        page: Int = 0,
        size: Int = 50,
        attemptsExhausted: Boolean? = null,
    ): Result<ListJobsResponse> = runCatching {
        repository.list(
            states = states,
            queue = queue,
            payloadType = payloadType,
            page = page,
            size = size,
            attemptsExhausted = attemptsExhausted,
        )
    }
}
