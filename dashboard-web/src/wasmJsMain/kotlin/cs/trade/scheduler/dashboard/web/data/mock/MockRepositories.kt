@file:OptIn(kotlin.time.ExperimentalTime::class)

package cs.trade.scheduler.dashboard.web.data.mock

import cs.trade.scheduler.dashboard.web.domain.repositories.JobsRepository
import cs.trade.scheduler.dashboard.web.domain.repositories.QueueHealthRepository
import cs.trade.scheduler.dashboard.web.domain.repositories.TypesRepository
import cs.trade.scheduler.shared.CancelResult
import cs.trade.scheduler.shared.DeleteResult
import cs.trade.scheduler.shared.JobPriority
import cs.trade.scheduler.shared.JobState
import cs.trade.scheduler.shared.RerouteResult
import cs.trade.scheduler.shared.RetryMode
import cs.trade.scheduler.shared.RetryResult
import cs.trade.scheduler.shared.dto.BulkActionResponse
import cs.trade.scheduler.shared.dto.JobDetail
import cs.trade.scheduler.shared.dto.JobView
import cs.trade.scheduler.shared.dto.ListJobsResponse
import cs.trade.scheduler.shared.dto.QueueHealthDto
import cs.trade.scheduler.shared.dto.QueueHealthStatus
import cs.trade.scheduler.shared.dto.TypePauseDto
import kotlin.time.Clock
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

// In-memory sample data for the `?mock` dev mode — lets the dashboard render populated screens
// with no backend (Docker / Postgres / Rabbit). Wired in Main.kt only when the URL carries
// `?mock`; production URLs never do, so this never ships data to a real deployment.

private val SAMPLE_QUEUES = listOf(
    "default", "email", "heavy", "reports", "billing", "imports", "exports", "webhooks",
)

private val SAMPLE_TYPES = listOf(
    "com.acme.email.SendEmail",
    "com.acme.report.GenerateReport",
    "com.acme.billing.ChargeCard",
    "com.acme.inventory.SyncInventory",
    "com.acme.media.ResizeImage",
    "com.acme.imports.ImportCsv",
    "com.acme.rollup.NightlyRollup",
    "com.acme.webhook.DeliverWebhook",
)

private val MOCK_JOBS: List<JobView> = run {
    val now = Clock.System.now()
    val states = JobState.entries
    (0 until 36).map { i ->
        val state = states[i % states.size]
        val attempts = when (state) {
            JobState.FAILED -> 3
            JobState.AWAITING_RETRY -> 1 + i % 2
            JobState.PROCESSING -> 1
            else -> 0
        }
        val hex = (0xC0FFEE00L + i * 977L).toString(16).takeLast(8).padStart(8, '0')
        val running = state == JobState.PROCESSING
        val counting = running && i % 3 == 0
        JobView(
            id = "$hex-1111-4111-8111-1111deadbeef",
            state = state,
            queue = SAMPLE_QUEUES[i % SAMPLE_QUEUES.size],
            priority = JobPriority(i % 11),
            payloadType = SAMPLE_TYPES[i % SAMPLE_TYPES.size],
            scheduledAt = if (state == JobState.SCHEDULED) now + (5 + i).minutes else null,
            attempts = attempts,
            maxAttempts = 3,
            lockedBy = if (running) "worker-${i % 3}" else null,
            progress = if (running && !counting) (0.15f * (i % 6 + 1)).coerceAtMost(0.95f) else null,
            progressMsg = if (running && !counting) "processing batch ${i + 1}" else null,
            progressSucceeded = if (counting) i * 12L else null,
            progressFailed = if (counting) (i % 4).toLong() else null,
            progressTotal = if (counting) i * 12L + 240 else null,
            durationMs = if (state == JobState.SUCCEEDED || state == JobState.FAILED) 120L + i * 37 else null,
            createdAt = now - (i * 7 + 3).minutes,
            updatedAt = now - (i + 1).minutes - (i % 5).seconds,
        )
    }
}

private val MOCK_QUEUE_HEALTH: List<QueueHealthDto> = listOf(
    QueueHealthDto("default", 14, QueueHealthStatus.NORMAL),
    QueueHealthDto("email", 1_840, QueueHealthStatus.ELEVATED),
    QueueHealthDto("heavy", 7_200, QueueHealthStatus.OVERLOADED),
    QueueHealthDto("reports", 6, QueueHealthStatus.NORMAL),
    QueueHealthDto("billing", 0, QueueHealthStatus.NORMAL),
    QueueHealthDto("imports", 1_120, QueueHealthStatus.ELEVATED),
    QueueHealthDto("exports", 3, QueueHealthStatus.NORMAL),
    QueueHealthDto("webhooks", 21, QueueHealthStatus.NORMAL),
)

/** Read-only sample job list. Filters + pagination work so the UI behaves like the real one. */
public class MockJobsRepository : JobsRepository {
    override suspend fun list(
        states: Set<JobState>,
        queue: String?,
        payloadType: String?,
        page: Int,
        size: Int,
        attemptsExhausted: Boolean?,
    ): ListJobsResponse {
        var rows = MOCK_JOBS
        if (states.isNotEmpty()) rows = rows.filter { it.state in states }
        if (!queue.isNullOrBlank()) rows = rows.filter { it.queue.contains(queue, ignoreCase = true) }
        if (!payloadType.isNullOrBlank()) rows = rows.filter { it.payloadType.contains(payloadType, ignoreCase = true) }
        if (attemptsExhausted == true) rows = rows.filter { it.attempts >= it.maxAttempts }
        val total = rows.size.toLong()
        val from = (page * size).coerceIn(0, rows.size)
        val to = (from + size).coerceIn(0, rows.size)
        return ListJobsResponse(items = rows.subList(from, to), total = total, page = page, size = size)
    }

    override suspend fun detail(jobId: String): JobDetail? = null
    override suspend fun cancel(jobId: String, by: String?): CancelResult = error(READ_ONLY)
    override suspend fun retry(jobId: String, by: String?, mode: RetryMode): RetryResult = error(READ_ONLY)
    override suspend fun delete(jobId: String, by: String?): DeleteResult = error(READ_ONLY)
    override suspend fun reroute(jobId: String, targetNode: String?, targetTag: String?, by: String?): RerouteResult =
        error(READ_ONLY)
    override suspend fun bulkRetry(ids: List<String>, by: String?): BulkActionResponse = error(READ_ONLY)
    override suspend fun bulkCancel(ids: List<String>, by: String?): BulkActionResponse = error(READ_ONLY)
    override suspend fun bulkDelete(ids: List<String>, by: String?): BulkActionResponse = error(READ_ONLY)

    private companion object {
        const val READ_ONLY = "Mock mode is read-only (?mock)"
    }
}

public class MockTypesRepository : TypesRepository {
    override suspend fun listPaused(): List<TypePauseDto> = listOf(
        TypePauseDto("com.acme.media.ResizeImage", Clock.System.now() - 2.hours, "ops@acme", "maintenance window"),
    )

    override suspend fun listKnown(): List<String> = SAMPLE_TYPES.sorted()
    override suspend fun pause(payloadType: String, reason: String?): Boolean = true
    override suspend fun unpause(payloadType: String): Boolean = true
}

public class MockQueueHealthRepository : QueueHealthRepository {
    override suspend fun list(): List<QueueHealthDto> = MOCK_QUEUE_HEALTH
}
