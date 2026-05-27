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
import kotlin.time.Duration

/**
 * Salvage step for jobs whose worker died mid-execution: find PROCESSING rows whose
 * `locked_until` is in the past, then for each one:
 *
 *   1. `markForRetry(backoff = 0)` — PROCESSING → AWAITING_RETRY, clear lock, version++.
 *      CAS-guarded by the snapshot version, so a worker that re-heartbeated just before
 *      we ran will safely win the race (its newer version makes our UPDATE a no-op).
 *   2. `outbox.insert(delay_ms = 0)` — gets republished by [OutboxPublisher] on its
 *      next tick, Rabbit dispatches to a worker, pickup runs as a regular retry.
 *
 * Note: `attempts` is NOT bumped here — `pickup` does that on the next attempt. So a
 * job that ran once, lost its worker, and got salvaged still records `attempts = 2`
 * on the next pickup, which is the right accounting (one execution attempt that
 * crashed + one fresh attempt).
 *
 * Returns the number of orphans actually re-queued (CAS conflicts skipped).
 *
 * Single replica per scheduler-infra container in MVP; multi-replica via
 * advisory-lock leader election is Phase 2 (DESIGN.md 14.3).
 */
public class RecoverOrphanedJobsUseCase(
    private val database: Database,
    private val jobs: JobRepository,
    private val outbox: OutboxRepository,
) : BaseUseCase() {

    private val log = LoggerFactory.getLogger(javaClass)

    public suspend operator fun invoke(batchSize: Int = DEFAULT_BATCH_SIZE): Result<Int> =
        runCatchingWithLogging {
            val orphans = jobs.findOrphaned(batchSize)
            if (orphans.isEmpty()) return@runCatchingWithLogging 0

            log.info("SafetyNet found {} orphan(s) — recovering", orphans.size)
            var recovered = 0
            for (orphan in orphans) {
                // Each orphan in its own transaction — one CAS failure shouldn't block
                // recovery of the others in the batch.
                val ok = withContext(Dispatchers.IO) {
                    suspendTransaction(db = database) {
                        val updated = jobs.markForRetry(
                            jobId = orphan.id,
                            expectedVersion = orphan.version,
                            backoff = Duration.ZERO,
                        )
                        if (!updated) return@suspendTransaction false
                        outbox.insert(
                            NewOutboxEntry(
                                jobId = orphan.id,
                                routingKey = routingKeyFor(orphan),
                                priority = orphan.priority.value,
                                delayMs = 0,
                            ),
                        )
                        true
                    }
                }
                if (ok) {
                    recovered++
                } else {
                    log.debug("Orphan {} skipped — version moved (worker heartbeated)", orphan.id)
                }
            }
            recovered
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
