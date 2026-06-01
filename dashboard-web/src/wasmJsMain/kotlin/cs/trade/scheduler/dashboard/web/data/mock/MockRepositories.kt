@file:OptIn(kotlin.time.ExperimentalTime::class)

package cs.trade.scheduler.dashboard.web.data.mock

import cs.trade.scheduler.dashboard.web.domain.repositories.JobsRepository
import cs.trade.scheduler.dashboard.web.domain.repositories.QueueHealthRepository
import cs.trade.scheduler.dashboard.web.domain.repositories.RecurringRepository
import cs.trade.scheduler.dashboard.web.domain.repositories.UpcomingRepository
import cs.trade.scheduler.dashboard.web.domain.repositories.StatsRepository
import cs.trade.scheduler.dashboard.web.domain.repositories.TypeStatsRepository
import cs.trade.scheduler.dashboard.web.domain.repositories.TypesRepository
import cs.trade.scheduler.dashboard.web.domain.repositories.WorkersRepository
import cs.trade.scheduler.shared.MisfirePolicy
import cs.trade.scheduler.shared.dto.RecurringJobDto
import cs.trade.scheduler.shared.dto.WorkerDto
import cs.trade.scheduler.shared.CancelResult
import cs.trade.scheduler.shared.DeleteResult
import cs.trade.scheduler.shared.JobPriority
import cs.trade.scheduler.shared.JobSortField
import cs.trade.scheduler.shared.JobState
import cs.trade.scheduler.shared.RerouteResult
import cs.trade.scheduler.shared.RetryMode
import cs.trade.scheduler.shared.RetryResult
import cs.trade.scheduler.shared.dto.BulkActionResponse
import cs.trade.scheduler.shared.OnFailure
import cs.trade.scheduler.shared.dto.JobDetail
import cs.trade.scheduler.shared.dto.JobEventDto
import cs.trade.scheduler.shared.dto.JobGraph
import cs.trade.scheduler.shared.dto.JobGraphEdge
import cs.trade.scheduler.shared.dto.JobView
import cs.trade.scheduler.shared.dto.ListJobsResponse
import cs.trade.scheduler.shared.dto.QueueHealthDto
import cs.trade.scheduler.shared.dto.QueueHealthStatus
import cs.trade.scheduler.shared.dto.StatsOverviewResponse
import cs.trade.scheduler.shared.dto.TypePauseDto
import cs.trade.scheduler.shared.dto.TypeStatsDto
import cs.trade.scheduler.shared.dto.TypeStatsResponse
import cs.trade.scheduler.shared.dto.UpcomingOccurrenceDto
import cs.trade.scheduler.shared.dto.UpcomingResponse
import cs.trade.scheduler.shared.dto.UpcomingSource
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Instant
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
        // Started once a worker has picked it up; still null for ENQUEUED/SCHEDULED/AWAITING_DEPS.
        val started = running ||
            state == JobState.SUCCEEDED || state == JobState.FAILED || state == JobState.AWAITING_RETRY
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
            startedAt = if (started) now - (i * 7 + 1).minutes else null,
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
        scheduledWithinMinutes: Int?,
        sortBy: JobSortField?,
        sortAscending: Boolean,
    ): ListJobsResponse {
        var rows = MOCK_JOBS
        if (states.isNotEmpty()) rows = rows.filter { it.state in states }
        if (!queue.isNullOrBlank()) rows = rows.filter { it.queue.contains(queue, ignoreCase = true) }
        if (!payloadType.isNullOrBlank()) rows = rows.filter { it.payloadType.contains(payloadType, ignoreCase = true) }
        if (attemptsExhausted == true) rows = rows.filter { it.attempts >= it.maxAttempts }
        if (scheduledWithinMinutes != null) {
            val now = Clock.System.now()
            val upper = now + scheduledWithinMinutes.minutes
            rows = rows.filter { r -> r.scheduledAt?.let { it >= now && it <= upper } == true }
                .sortedBy { it.scheduledAt }
        } else if (sortBy != null) {
            val cmp: Comparator<JobView> = when (sortBy) {
                JobSortField.CREATED -> compareBy { it.createdAt }
                JobSortField.UPDATED -> compareBy { it.updatedAt }
                JobSortField.STARTED -> compareBy { it.startedAt }
                JobSortField.ATTEMPTS -> compareBy { it.attempts }
                JobSortField.PRIORITY -> compareBy { it.priority.value }
                JobSortField.QUEUE -> compareBy { it.queue }
                JobSortField.TYPE -> compareBy { it.payloadType }
                JobSortField.STATE -> compareBy { it.state.name }
            }
            rows = if (sortAscending) rows.sortedWith(cmp) else rows.sortedWith(cmp.reversed())
        }
        val total = rows.size.toLong()
        val from = (page * size).coerceIn(0, rows.size)
        val to = (from + size).coerceIn(0, rows.size)
        return ListJobsResponse(items = rows.subList(from, to), total = total, page = page, size = size)
    }

    // Synthesises a full detail (timeline + diamond DAG) around whichever job was clicked, so the
    // JobDetail screen renders populated under ?mock. Unknown ids (e.g. a DAG neighbour click)
    // fall back to the first sample job re-stamped with that id.
    override suspend fun detail(jobId: String): JobDetail {
        val now = Clock.System.now()
        val focal = MOCK_JOBS.firstOrNull { it.id == jobId } ?: MOCK_JOBS.first().copy(id = jobId)
        val payloadJson =
            "{\n  \"to\": \"user@acme.com\",\n  \"template\": \"welcome\",\n  \"locale\": \"en\",\n  \"attempt\": ${focal.attempts}\n}"

        fun node(prefix: String, type: String, state: JobState): JobView = focal.copy(
            id = "$prefix-2222-4222-8222-2222feedface",
            state = state,
            payloadType = type,
            progress = null, progressMsg = null,
            progressSucceeded = null, progressFailed = null, progressTotal = null,
            lockedBy = null,
        )
        val rootA = node("aaaaaaaa", "com.acme.inventory.SyncInventory", JobState.SUCCEEDED)
        val rootB = node("bbbbbbbb", "com.acme.media.ResizeImage", JobState.SUCCEEDED)
        val leaf1 = node("cccccccc", "com.acme.report.GenerateReport", JobState.AWAITING_DEPS)
        val leaf2 = node(
            "dddddddd", "com.acme.webhook.DeliverWebhook",
            if (focal.state == JobState.FAILED) JobState.CANCELLED else JobState.AWAITING_DEPS,
        )
        val graph = JobGraph(
            nodes = listOf(rootA, rootB, focal, leaf1, leaf2),
            edges = listOf(
                JobGraphEdge(rootA.id, focal.id, OnFailure.PROPAGATE_FAILURE),
                JobGraphEdge(rootB.id, focal.id, OnFailure.PROPAGATE_FAILURE),
                JobGraphEdge(focal.id, leaf1.id, OnFailure.PROPAGATE_FAILURE),
                JobGraphEdge(focal.id, leaf2.id, OnFailure.CANCEL_CHILD),
            ),
        )
        return JobDetail(job = focal, payloadJson = payloadJson, events = mockEvents(focal, now), graph = graph)
    }

    override suspend fun cancel(jobId: String, by: String?): CancelResult = error(READ_ONLY)
    override suspend fun retry(jobId: String, by: String?, mode: RetryMode): RetryResult = error(READ_ONLY)
    override suspend fun delete(jobId: String, by: String?): DeleteResult = error(READ_ONLY)

    // Re-run is a "create", not a mutation of existing rows, so we don't fail it like the others —
    // returning a sample id lets the Run/Re-run navigation flow be exercised under ?mock.
    override suspend fun rerun(jobId: String): String = MOCK_JOBS[1].id
    override suspend fun reroute(jobId: String, targetNode: String?, targetTag: String?, by: String?): RerouteResult =
        error(READ_ONLY)
    override suspend fun bulkRetry(ids: List<String>, by: String?): BulkActionResponse = error(READ_ONLY)
    override suspend fun bulkCancel(ids: List<String>, by: String?): BulkActionResponse = error(READ_ONLY)
    override suspend fun bulkDelete(ids: List<String>, by: String?): BulkActionResponse = error(READ_ONLY)

    private companion object {
        const val READ_ONLY = "Mock mode is read-only (?mock)"
    }
}

// A plausible timeline for the focal job's current state — enqueue, then the run / failure /
// retry / cancel transitions a real job of that state would have logged.
private fun mockEvents(job: JobView, now: Instant): List<JobEventDto> {
    var id = 0L
    val events = mutableListOf<JobEventDto>()
    fun add(
        type: String,
        prev: JobState?,
        new: JobState?,
        ago: Duration,
        actor: String? = null,
        msg: String? = null,
        stack: String? = null,
    ) {
        events += JobEventDto(id++, job.id, type, prev, new, actor, msg, stack, now - ago)
    }
    add("ENQUEUED", null, JobState.ENQUEUED, 30.minutes)
    when (job.state) {
        JobState.PROCESSING ->
            add("STARTED", JobState.ENQUEUED, JobState.PROCESSING, 6.minutes, actor = job.lockedBy ?: "worker-1")
        JobState.SUCCEEDED -> {
            add("STARTED", JobState.ENQUEUED, JobState.PROCESSING, 12.minutes, actor = "worker-1")
            add("SUCCEEDED", JobState.PROCESSING, JobState.SUCCEEDED, 11.minutes)
        }
        JobState.FAILED -> {
            add("STARTED", JobState.ENQUEUED, JobState.PROCESSING, 12.minutes, actor = "worker-2")
            add(
                "FAILED", JobState.PROCESSING, JobState.FAILED, 11.minutes,
                msg = "Connection reset by peer (smtp.acme.com:587)", stack = SAMPLE_STACK,
            )
        }
        JobState.AWAITING_RETRY -> {
            add("STARTED", JobState.ENQUEUED, JobState.PROCESSING, 12.minutes, actor = "worker-0")
            add(
                "FAILED", JobState.PROCESSING, JobState.AWAITING_RETRY, 11.minutes,
                msg = "Timed out after 30000ms", stack = SAMPLE_STACK,
            )
            add("RETRY", JobState.AWAITING_RETRY, JobState.ENQUEUED, 9.minutes, actor = "scheduler")
        }
        JobState.CANCELLED ->
            add("CANCELLED", JobState.ENQUEUED, JobState.CANCELLED, 5.minutes, actor = "ops@acme")
        else -> {}
    }
    return events
}

private val SAMPLE_STACK = """
    java.net.SocketException: Connection reset by peer
        at java.base/sun.nio.ch.Net.translateToSocketException(Net.java:179)
        at com.acme.mailer.SmtpClient.send(SmtpClient.kt:142)
        at com.acme.email.SendEmailHandler.handle(SendEmailHandler.kt:38)
        at cs.trade.scheduler.engine.worker.JobRunner.invoke(JobRunner.kt:91)
""".trimIndent()

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

public class MockStatsRepository : StatsRepository {
    // A healthy cluster: a deep-ish enqueued backlog, a handful in flight, and a ~99% success rate.
    // Live states are "now" (constant); terminal outcomes are baselined per-24h and scaled to the
    // requested window so switching the range selector visibly changes the donut + outcome KPIs.
    override suspend fun overview(rangeHours: Int): StatsOverviewResponse {
        fun scale(per24h: Long): Long = (per24h.toDouble() * rangeHours / 24.0).toLong()
        return StatsOverviewResponse(
            enqueued = 1240,
            processing = 42,
            awaitingRetry = 18,
            awaitingDeps = 64,
            scheduled = 300,
            succeeded = scale(184_500),
            failed = scale(1_490),
            cancelled = scale(860),
        )
    }
}

public class MockTypeStatsRepository : TypeStatsRepository {
    // Counts are baselined per-24h, then scaled linearly to the requested window so switching
    // the range selector visibly changes the table. Durations are averages — they don't scale.
    override suspend fun list(rangeHours: Int): TypeStatsResponse {
        fun scale(per24h: Long): Long = (per24h.toDouble() * rangeHours / 24.0).toLong()
        val items = MOCK_TYPE_STATS_24H.map { b ->
            b.copy(
                successCount = scale(b.successCount),
                failedCount = scale(b.failedCount),
                cancelledCount = scale(b.cancelledCount),
                retryCount = scale(b.retryCount),
            )
        }
        return TypeStatsResponse(items = items, rangeHours = rangeHours)
    }
}

// Per-24h baseline rows: high-volume webhooks, a slow report type, a billing type with notable
// failures, a clean all-success sync, and an import type with NULL durations (cancelled-heavy).
private val MOCK_TYPE_STATS_24H: List<TypeStatsDto> = listOf(
    TypeStatsDto("com.acme.webhook.DeliverWebhook", "webhooks", 15200, 430, 12, 890, 64, 8, 5000, 320),
    TypeStatsDto("com.acme.email.SendEmail", "email", 4820, 12, 3, 45, 28, 5, 320, 95),
    TypeStatsDto("com.acme.inventory.SyncInventory", "default", 2400, 0, 0, 0, 88, 12, 540, 210),
    TypeStatsDto("com.acme.billing.ChargeCard", "billing", 980, 64, 5, 130, 540, 120, 9000, 2100),
    TypeStatsDto("com.acme.media.ResizeImage", "heavy", 760, 21, 40, 60, 1500, 200, 22000, 8800),
    TypeStatsDto("com.acme.report.GenerateReport", "reports", 142, 8, 1, 22, 4200, 1100, 38000, 21000),
    TypeStatsDto("com.acme.imports.ImportCsv", "imports", 36, 4, 9, 14, null, null, null, null),
)

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
        row(
            "marketplace.market_csgo.update_market_cs_go_item_buy_order_prises_task",
            "*/30 * * * *", "marketplace", "com.acme.marketplace.UpdateBuyOrderPrices", "UTC", true, 12, 18, 6,
        ),
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

    // "Run now" — returns a sample job id so the screen can navigate to a (synthesised) detail.
    override suspend fun trigger(id: String): String = MOCK_JOBS.first().id
}

public class MockUpcomingRepository : UpcomingRepository {
    // Synthesises a forward agenda from the sample recurring rows: each enabled definition repeats
    // across the window at a pseudo-period, so the screen shows realistic repetitions under ?mock.
    override suspend fun upcoming(withinMinutes: Int): UpcomingResponse {
        val now = Clock.System.now()
        val upper = now + withinMinutes.minutes
        val items = buildList {
            MOCK_RECURRING.filter { it.enabled }.forEachIndexed { idx, r ->
                val periodSec = (10 + idx * 8).toLong()
                var t = now + periodSec.seconds
                var count = 0
                while (t <= upper && count < 20) {
                    add(
                        UpcomingOccurrenceDto(
                            at = t,
                            source = UpcomingSource.RECURRING,
                            payloadType = r.payloadType,
                            queue = r.queue,
                            id = r.id,
                            cron = r.cron,
                        ),
                    )
                    t += periodSec.seconds
                    count++
                }
            }
        }.sortedBy { it.at }
        return UpcomingResponse(items = items.take(200), truncated = items.size > 200, windowMinutes = withinMinutes)
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
