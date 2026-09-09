@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package cs.trade.scheduler.engine.infra.domain.usecases

import cs.trade.scheduler.core.backend.usecases.BaseUseCase
import cs.trade.scheduler.core.backend.usecases.runCatchingWithLogging
import cs.trade.scheduler.storage.postgres.domain.models.Job
import cs.trade.scheduler.storage.postgres.domain.models.NewOutboxEntry
import cs.trade.scheduler.storage.postgres.domain.repositories.JobRepository
import cs.trade.scheduler.storage.postgres.domain.repositories.OutboxRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.transactions.suspendTransaction
import org.slf4j.LoggerFactory
import kotlin.time.Clock
import kotlin.time.Duration

/**
 * Salvage step for jobs whose *broker message* was lost: the row sits in ENQUEUED, but no
 * message will ever arrive for it. [RecoverOrphanedJobsUseCase] does not cover this — it
 * only reclaims PROCESSING rows by expired lock, and an ENQUEUED row has no lock to expire.
 *
 * Left alone, one lost message stops a whole recurring channel indefinitely: `SKIP` overlap
 * refuses to enqueue the next tick while the previous job is non-terminal. Observed in
 * production 2026-09-09 — 18 `q.default` jobs published during a worker restart never
 * arrived, and trade-offer intake, selling and delivery stood still for 74 minutes until an
 * operator cancelled the rows by hand.
 *
 * ## Deciding that a message is gone
 *
 * Age alone cannot say it: a job queued behind a long predecessor (`q.heavy`, concurrency 1)
 * looks exactly like a lost one. The distinguishing fact is **overtaking**. A broker hands a
 * queue out in (priority, publish order), so if jobs of equal-or-lower priority that were
 * created *later* have already started, the queue has moved past this row — its message
 * cannot still be waiting. A job that is merely queued is never overtaken this way.
 *
 * Both conditions must hold: untouched for [staleFor] **and** overtaken.
 *
 * ## Why re-publishing is safe
 *
 * The row is left in ENQUEUED and only an outbox entry is added, so recovery is a duplicate
 * *message*, never a duplicate *execution*: pickup is a CAS on (id, version), so if the
 * original message does turn up after all, one delivery wins and the other is acknowledged
 * without running anything. Touching `updated_at` keeps the next attempt at least
 * [staleFor] away, so a genuinely unlucky job cannot be republished in a tight loop.
 *
 * Returns the number of jobs re-published.
 */
public class RecoverStuckEnqueuedJobsUseCase(
    private val database: Database,
    private val jobs: JobRepository,
    private val outbox: OutboxRepository,
) : BaseUseCase() {

    private val log = LoggerFactory.getLogger(javaClass)

    public suspend operator fun invoke(
        staleFor: Duration,
        batchSize: Int = DEFAULT_BATCH_SIZE,
    ): Result<Int> = runCatchingWithLogging {
        val candidates = jobs.findEnqueuedOlderThan(staleFor = staleFor, limit = batchSize)
        if (candidates.isEmpty()) return@runCatchingWithLogging 0

        // Look back far enough to cover the oldest candidate: a watermark newer than it is
        // exactly what proves the queue moved on.
        val oldest = candidates.minOf { candidate -> candidate.createdAt }
        val watermarks = jobs.findStartedWatermarks(since = oldest)

        val stuck = candidates.filter { candidate -> isOvertaken(candidate, watermarks) }
        if (stuck.isEmpty()) return@runCatchingWithLogging 0

        log.warn(
            "SafetyNet found {} ENQUEUED job(s) with no broker message (stale > {}, overtaken by newer jobs) — re-publishing",
            stuck.size,
            staleFor,
        )

        var republished = 0
        for (job in stuck) {
            // Each in its own transaction — one failure shouldn't block the rest of the batch.
            val ok = withContext(Dispatchers.IO) {
                suspendTransaction(db = database) {
                    // Re-check under the row's own version: if a worker picked the job up
                    // between our SELECT and now, it is no longer ENQUEUED and needs nothing.
                    val touched = jobs.touchUpdatedAt(jobId = job.id, expectedVersion = job.version)
                    if (!touched) return@suspendTransaction false
                    outbox.insert(
                        NewOutboxEntry(
                            jobId = job.id,
                            routingKey = routingKeyFor(job),
                            priority = job.priority.value,
                            delayMs = 0,
                        ),
                    )
                    true
                }
            }
            if (ok) {
                republished++
                log.warn("Re-published lost message for job {} ({} on {})", job.id, job.payloadType, job.queue)
            } else {
                log.debug("Stuck-enqueued {} skipped — row moved on between scan and recovery", job.id)
            }
        }
        republished
    }

    private fun isOvertaken(job: Job, watermarks: List<JobRepository.QueueWatermark>): Boolean =
        watermarks.any { watermark ->
            watermark.queue == job.queue &&
                watermark.priority <= job.priority.value &&
                watermark.latestCreatedAt > job.createdAt
        }

    private fun routingKeyFor(job: Job): String = when {
        job.targetNode != null -> "node.${job.targetNode}"
        job.targetTag != null -> "tag.${job.targetTag}"
        else -> job.queue
    }

    public companion object {
        public const val DEFAULT_BATCH_SIZE: Int = 100
    }
}
