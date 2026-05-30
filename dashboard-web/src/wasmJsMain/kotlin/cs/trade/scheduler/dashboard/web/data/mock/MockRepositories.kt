@file:OptIn(kotlin.time.ExperimentalTime::class)

package cs.trade.scheduler.dashboard.web.data.mock

import cs.trade.scheduler.dashboard.web.domain.repositories.JobsRepository
import cs.trade.scheduler.dashboard.web.domain.repositories.QueueHealthRepository
import cs.trade.scheduler.dashboard.web.domain.repositories.RecurringRepository
import cs.trade.scheduler.dashboard.web.domain.repositories.TypesRepository
import cs.trade.scheduler.dashboard.web.domain.repositories.WorkersRepository
import cs.trade.scheduler.shared.MisfirePolicy
import cs.trade.scheduler.shared.dto.RecurringJobDto
import cs.trade.scheduler.shared.dto.WorkerDto
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
import kotlin.time.Duration.Companion.days
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
    override suspend fun listPaused(): List<TypePauseDto> = run {
        val now = Clock.System.now()
        listOf(
            TypePauseDto("com.acme.media.ResizeImage", now - 2.hours, "ops@acme", "maintenance window"),
            TypePauseDto("com.acme.billing.ChargeCard", now - 35.minutes, "alice@acme", "payment gateway incident #4821"),
            TypePauseDto("com.acme.webhook.DeliverWebhook", now - 3.days, "scheduler", null),
        )
    }

    override suspend fun listKnown(): List<String> = SAMPLE_TYPES.sorted()
    override suspend fun pause(payloadType: String, reason: String?): Boolean = true
    override suspend fun unpause(payloadType: String): Boolean = true
}

public class MockQueueHealthRepository : QueueHealthRepository {
    override suspend fun list(): List<QueueHealthDto> = MOCK_QUEUE_HEALTH
}

private val MOCK_RECURRING: List<RecurringJobDto> = run {
    val now = Clock.System.now()
    fun row(
        id: String,
        cron: String,
        queue: String,
        payloadType: String,
        timezone: String?,
        enabled: Boolean,
        lastAgoMin: Int?,
        nextInMin: Int,
        priority: Int,
    ) = RecurringJobDto(
        id = id,
        cron = cron,
        timezone = timezone,
        misfirePolicy = MisfirePolicy.CATCH_UP_ONE,
        queue = queue,
        priority = JobPriority(priority),
        targetNode = null,
        targetTag = null,
        payloadType = payloadType,
        lastTriggeredAt = lastAgoMin?.let { now - it.minutes },
        nextTriggerAt = now + nextInMin.minutes,
        enabled = enabled,
    )
    listOf(
        row("nightly-report", "0 3 * * *", "reports", "com.acme.report.GenerateReport", "Europe/Berlin", true, 600, 720, 5),
        row("hourly-inventory-sync", "0 * * * *", "default", "com.acme.inventory.SyncInventory", null, true, 35, 25, 0),
        row("healthcheck-10s", "*/10 * * * * *", "default", "com.acme.webhook.DeliverWebhook", null, true, 0, 0, 0),
        row("weekly-billing", "0 6 * * MON", "billing", "com.acme.billing.ChargeCard", "America/New_York", true, 4320, 5760, 8),
        row("daily-cleanup", "30 2 * * *", "heavy", "com.acme.imports.ImportCsv", null, false, 1440, 90, 2),
        row("monthly-rollup", "0 0 1 * *", "reports", "com.acme.rollup.NightlyRollup", "UTC", true, null, 86400, 3),
        row("email-digest", "0 9 * * 1-5", "email", "com.acme.email.SendEmail", "Europe/Berlin", false, 180, 240, 4),
    )
}

public class MockRecurringRepository : RecurringRepository {
    // Toggling actually flips the row in mock mode, so Enable/Disable is interactive.
    private val enabledOverride = mutableMapOf<String, Boolean>()

    override suspend fun list(): List<RecurringJobDto> =
        MOCK_RECURRING.map { it.copy(enabled = enabledOverride[it.id] ?: it.enabled) }

    override suspend fun enable(id: String): Boolean {
        enabledOverride[id] = true
        return true
    }

    override suspend fun disable(id: String): Boolean {
        enabledOverride[id] = false
        return true
    }
}

private val MOCK_WORKERS: List<WorkerDto> = run {
    val now = Clock.System.now()
    fun w(
        nodeId: String,
        host: String,
        tags: List<String>,
        startedAgoMin: Int,
        hbAgoSec: Int,
        inFlight: Int,
        byQueue: Map<String, Int>,
        alive: Boolean,
    ) = WorkerDto(
        nodeId = nodeId,
        host = host,
        tags = tags,
        startedAt = now - startedAgoMin.minutes,
        lastHeartbeat = now - hbAgoSec.seconds,
        inFlightCount = inFlight,
        inFlightByQueue = byQueue,
        alive = alive,
    )
    listOf(
        w("worker-api-1", "ip-10-0-1-12", listOf("api", "fast"), 320, 4, 7, mapOf("default" to 4, "email" to 3), true),
        w("worker-api-2", "ip-10-0-1-13", listOf("api", "fast"), 320, 9, 3, mapOf("default" to 3), true),
        w("worker-heavy-1", "ip-10-0-2-7", listOf("heavy"), 1_440, 12, 2, mapOf("heavy" to 2), true),
        w("worker-reports-1", "ip-10-0-3-21", listOf("reports", "cron"), 60, 2, 0, emptyMap(), true),
        w("worker-legacy-9", "ip-10-0-9-99", emptyList(), 5_760, 180, 0, emptyMap(), false),
    )
}

public class MockWorkersRepository : WorkersRepository {
    override suspend fun list(): List<WorkerDto> = MOCK_WORKERS
}
