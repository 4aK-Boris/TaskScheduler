@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package cs.trade.scheduler.dashboard.server.domain.usecases

import cs.trade.scheduler.core.backend.cron.CronExpr
import cs.trade.scheduler.core.backend.usecases.BaseUseCase
import cs.trade.scheduler.core.backend.usecases.runCatchingWithLogging
import cs.trade.scheduler.shared.dto.UpcomingOccurrenceDto
import cs.trade.scheduler.shared.dto.UpcomingResponse
import cs.trade.scheduler.shared.dto.UpcomingSource
import cs.trade.scheduler.storage.postgres.domain.models.JobListFilter
import cs.trade.scheduler.storage.postgres.domain.repositories.JobRepository
import cs.trade.scheduler.storage.postgres.domain.repositories.RecurringJobRepository
import org.koin.core.annotation.Single
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes

/**
 * Builds the "Upcoming" agenda — every task run predicted to fire within the next [windowMinutes],
 * soonest-first. Two sources, merged:
 *
 *  1. **Recurring** — each enabled definition's cron is expanded into concrete slots in (now, upper]
 *     via [CronExpr]. The same definition repeats once per slot (a 10s cron in a 1h window yields
 *     many rows — that's the point). A bad/expired cron is skipped rather than failing the whole call.
 *  2. **Jobs** — real future-dated `job` rows already in the window (a one-off `scheduleAt`, or a
 *     failed job waiting on its backoff retry), via the same `scheduled_at` filter the list uses.
 *
 * Capped so a sub-second cron can't flood the response: [PER_RECURRING_CAP] slots per definition,
 * [LIMIT] rows overall (the soonest). `truncated` reports either cap so the UI can say so.
 */
@Single
public class GetUpcomingUseCase(
    private val recurring: RecurringJobRepository,
    private val jobs: JobRepository,
) : BaseUseCase() {

    public suspend operator fun invoke(windowMinutes: Int): Result<UpcomingResponse> = runCatchingWithLogging {
        val now = Clock.System.now()
        val upper = now + windowMinutes.minutes
        var truncated = false

        val recurringItems = buildList {
            for (row in recurring.findAll()) {
                if (!row.enabled) continue
                var slot = runCatching { CronExpr.nextAfter(row.cron, now, row.timezone) }.getOrNull() ?: continue
                var count = 0
                while (slot <= upper) {
                    if (count >= PER_RECURRING_CAP) {
                        truncated = true
                        break
                    }
                    add(
                        UpcomingOccurrenceDto(
                            at = slot,
                            source = UpcomingSource.RECURRING,
                            payloadType = row.payloadType,
                            queue = row.queue,
                            id = row.id,
                            cron = row.cron,
                        ),
                    )
                    count++
                    slot = runCatching { CronExpr.nextAfter(row.cron, slot, row.timezone) }.getOrNull() ?: break
                }
            }
        }

        val jobItems = jobs.findAll(JobListFilter(scheduledWithinMinutes = windowMinutes), page = 0, size = LIMIT)
            .items
            .mapNotNull { job ->
                val at = job.scheduledAt ?: return@mapNotNull null
                UpcomingOccurrenceDto(
                    at = at,
                    source = UpcomingSource.JOB,
                    payloadType = job.payloadType,
                    queue = job.queue,
                    id = job.id.toString(),
                    state = job.state,
                )
            }

        val merged = (recurringItems + jobItems).sortedBy { it.at }
        if (merged.size > LIMIT) truncated = true
        UpcomingResponse(items = merged.take(LIMIT), truncated = truncated, windowMinutes = windowMinutes)
    }

    public companion object {
        /** Overall cap on returned rows — the soonest N across both sources. */
        public const val LIMIT: Int = 200

        /** Per-definition slot cap so one sub-second cron can't dominate the merge. */
        public const val PER_RECURRING_CAP: Int = 100
    }
}
