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

    /**
     * Oldest *publishable* unpublished entries, FIFO. Limit caps batch size for the
     * publisher loop. Rows whose job's `payload_type` is paused (`job_type_pause`,
     * DESIGN.md 22.1) are excluded at SQL level — they park in the outbox until unpause.
     * The exclusion must live in the query, not in caller code: paused rows sit at the
     * head of the id-ordered scan, and once a batch-size of them accumulates, an
     * unfiltered LIMIT window contains only paused rows and every other type starves.
     */
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

    /**
     * Count of *publishable* unpublished rows (same paused-type exclusion as
     * [findUnpublished]). Feeds the publisher backlog WARN and the
     * `scheduler_outbox_unpublished` gauge — rows parked by an operator pause are not
     * "the publisher falling behind" and would otherwise keep the alert in permanent
     * breach for the whole life of a long pause.
     */
    public suspend fun countUnpublished(): Long

    /**
     * `created_at` of the oldest *publishable* unpublished outbox row (same paused-type
     * exclusion as [findUnpublished]), or null if none. Used by the metrics poller to
     * compute `scheduler_outbox_lag_seconds = now - oldest`.
     */
    public suspend fun findOldestUnpublishedCreatedAt(): kotlin.time.Instant?
}
