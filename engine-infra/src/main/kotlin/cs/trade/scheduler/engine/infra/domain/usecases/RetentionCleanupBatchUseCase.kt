@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package cs.trade.scheduler.engine.infra.domain.usecases

import cs.trade.scheduler.core.backend.archival.ArchivalSink
import cs.trade.scheduler.core.backend.usecases.BaseUseCase
import cs.trade.scheduler.core.backend.usecases.runCatchingWithLogging
import cs.trade.scheduler.engine.infra.infrastructure.SchedulerInfraConfig
import cs.trade.scheduler.shared.JobState
import cs.trade.scheduler.storage.postgres.domain.repositories.IdempotencyLogRepository
import cs.trade.scheduler.storage.postgres.domain.repositories.JobRepository
import cs.trade.scheduler.storage.postgres.domain.repositories.OutboxRepository
import cs.trade.scheduler.storage.postgres.domain.repositories.WorkerRepository
import cs.trade.scheduler.storage.postgres.infrastructure.archival.toArchivedRecord
import org.slf4j.LoggerFactory
import kotlin.time.Clock
import kotlin.time.Duration

/**
 * One pass of retention cleanup. Walks each `RetentionConfig` bucket and DELETEs the
 * matching rows older than `now - retention.<bucket>`. A `null` retention disables that
 * bucket — useful for "keep failed jobs forever" or "outbox cleanup off in tests".
 *
 * Batched by [SchedulerInfraConfig.cleanupBatchSize] (default 10k) so one tick can't
 * lock the table for arbitrary minutes on a backlog. The next tick picks up where we
 * left off.
 *
 * **Archival hook (DESIGN.md 18.7):** terminal job rows pass through [ArchivalSink]
 * before DELETE. Default sink is [ArchivalSink.Noop] (zero-cost — just a method-call
 * indirection); deployments needing forensic retention override the Koin binding with
 * a file/S3/GCS impl. If the sink throws, the corresponding DELETE is SKIPPED so the
 * rows live another tick and the sink gets another shot. We don't archive
 * outbox/worker/idempotency rows — only `job` is in scope today (its terminal state
 * has all the forensically interesting bits already).
 *
 * Returns total rows deleted across all buckets — handy for dashboards / metrics.
 */
public class RetentionCleanupBatchUseCase(
    private val jobs: JobRepository,
    private val outbox: OutboxRepository,
    private val workers: WorkerRepository,
    private val idempotencyLog: IdempotencyLogRepository,
    private val archivalSink: ArchivalSink,
    private val config: SchedulerInfraConfig,
) : BaseUseCase() {

    private val log = LoggerFactory.getLogger(javaClass)

    public suspend operator fun invoke(): Result<Int> = runCatchingWithLogging {
        val now = Clock.System.now()
        val batchSize = config.cleanupBatchSize
        val retention = config.retention

        var total = 0
        total += archiveAndDelete(JobState.SUCCEEDED, retention.succeeded, now, batchSize)
        total += archiveAndDelete(JobState.FAILED, retention.failed, now, batchSize)
        total += archiveAndDelete(JobState.CANCELLED, retention.cancelled, now, batchSize)

        if (retention.outboxPublished != null) {
            val deleted = outbox.deletePublishedOlderThan(
                olderThan = now - retention.outboxPublished!!,
                batchSize = batchSize,
            )
            if (deleted > 0) log.info("Retention: deleted {} published outbox row(s)", deleted)
            total += deleted
        }

        // Dead workers: rows whose last_heartbeat is older than the configured threshold.
        // WorkerPool.stop() already nukes the row on graceful shutdown — this catches the
        // pods/processes that died unceremoniously (kill -9, host evicted, etc.).
        if (retention.deadWorkers != null) {
            val deleted = workers.deleteDeadOlderThan(olderThan = now - retention.deadWorkers!!)
            if (deleted > 0) log.info("Retention: deleted {} dead worker row(s)", deleted)
            total += deleted
        }

        // idempotency_log: independent TTL, no FK to job (DESIGN.md 18.4). Default 30d
        // — longer than terminal-job retention so external API idempotency keys
        // (Stripe-style `Idempotency-Key: ${jobId}`) outlive the originating row.
        if (retention.idempotencyLog != null) {
            val deleted = idempotencyLog.deleteOlderThan(
                olderThan = now - retention.idempotencyLog!!,
                batchSize = batchSize,
            )
            if (deleted > 0) log.info("Retention: deleted {} idempotency_log row(s)", deleted)
            total += deleted
        }

        total
    }

    private suspend fun archiveAndDelete(
        state: JobState,
        retention: Duration?,
        now: kotlin.time.Instant,
        batchSize: Int,
    ): Int {
        if (retention == null) return 0
        val batch = jobs.findTerminalOlderThan(state, now - retention, batchSize)
        if (batch.isEmpty()) return 0

        val category = "job.${state.name.lowercase()}"
        try {
            archivalSink.archive(category, batch.map { it.toArchivedRecord() })
        } catch (t: Throwable) {
            // Don't propagate: the parent runCatchingWithLogging would mark the whole
            // tick as failed, hiding the other buckets that might have succeeded. Log
            // and move on; the rows live another tick and the sink gets another shot.
            log.error(
                "Archival sink threw for category={} (skipping DELETE for {} row(s) — next tick will retry)",
                category, batch.size, t,
            )
            return 0
        }

        val deleted = jobs.deleteByIdsInState(batch.map { it.id }, state)
        if (deleted > 0) log.info("Retention: archived + deleted {} {} job row(s)", deleted, state)
        return deleted
    }
}
