@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package cs.trade.scheduler.storage.postgres.domain.repositories

import cs.trade.scheduler.storage.postgres.domain.models.IdempotencyEntry
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * Persists handler-side dedup marks. Backs [cs.trade.scheduler.core.backend.idempotency.IdempotencyStore]
 * — most callers should depend on the higher-level interface; this repo is for the
 * retention loop and the dashboard "show me the idempotency log of job X" debug view.
 */
public interface IdempotencyLogRepository {

    /**
     * Atomically inserts `(jobId, action)` with `occurred_at = now()` via
     * `INSERT ... ON CONFLICT DO NOTHING`. Returns `true` if the row was inserted
     * (first mark), `false` if it already existed (duplicate execution).
     *
     * The PRIMARY KEY on `(job_id, action)` provides the race-free guarantee — two
     * concurrent workers calling this on the same key always agree on who got `true`.
     */
    public suspend fun tryMark(jobId: Uuid, action: String = "default"): Boolean

    /**
     * Returns all marks for a single job, sorted by `occurred_at` ascending. Used by
     * the dashboard JobDetail "Idempotency" tab so operators can see which steps of a
     * multi-step handler completed. Bounded by [limit] — multi-step jobs are typically
     * <10 actions, the default catches degenerate cases.
     */
    public suspend fun findByJobId(jobId: Uuid, limit: Int = 100): List<IdempotencyEntry>

    /**
     * Retention cleanup. Deletes rows whose `occurred_at < olderThan`, capped at
     * [batchSize] to keep one tick from holding the table lock for arbitrary minutes.
     * Returns the row count actually deleted — the loop tracks total for ops metrics.
     *
     * Independent TTL (no FK to `job`, see DESIGN.md 18.4) — typically longer than
     * job retention so external API idempotency keys outlive the originating job.
     */
    public suspend fun deleteOlderThan(olderThan: Instant, batchSize: Int): Int
}
