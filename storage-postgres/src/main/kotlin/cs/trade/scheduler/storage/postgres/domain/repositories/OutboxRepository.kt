package cs.trade.scheduler.storage.postgres.domain.repositories

import cs.trade.scheduler.storage.postgres.domain.models.NewOutboxEntry
import cs.trade.scheduler.storage.postgres.domain.models.OutboxEntry

/**
 * Reads + writes for the transactional outbox (DESIGN.md section 4.1).
 *
 * `insert` is the only writer called from [cs.trade.scheduler.core.backend.Scheduler.enqueue];
 * `findUnpublished` + `markPublished` are consumed by `OutboxPublisher` in `:engine-infra`.
 */
public interface OutboxRepository {

    /**
     * INSERT a new outbox row. Must be called inside the same DB transaction that
     * inserts the parent `job` row (DESIGN.md section 7.1).
     */
    public suspend fun insert(entry: NewOutboxEntry): OutboxEntry

    /** Oldest unpublished entries, FIFO. Limit caps batch size for the publisher loop. */
    public suspend fun findUnpublished(limit: Int): List<OutboxEntry>

    /** Mark a published entry. Returns true if updated, false if id missing or already marked. */
    public suspend fun markPublished(id: Long): Boolean

    /**
     * DELETE outbox rows whose `published_at < olderThan`. Bounded by [batchSize].
     * Returns the number of rows deleted. Called by RetentionCleanup loop on the
     * `retention.outboxPublished` schedule (default 1h — keeps the table tiny since
     * outbox rows are throwaway after publishing).
     */
    public suspend fun deletePublishedOlderThan(olderThan: kotlin.time.Instant, batchSize: Int): Int

    /** `SELECT COUNT(*) FROM outbox WHERE published_at IS NULL`. Cheap (partial index). */
    public suspend fun countUnpublished(): Long

    /**
     * `created_at` of the oldest unpublished outbox row, or null if none. Used by the
     * metrics poller to compute `scheduler_outbox_lag_seconds = now - oldest`. Single-row
     * read off the `outbox_unpublished_idx` partial index — O(1).
     */
    public suspend fun findOldestUnpublishedCreatedAt(): kotlin.time.Instant?
}
