package cs.trade.scheduler.core.backend.archival

import cs.trade.scheduler.shared.archival.ArchivedJobRecord

/**
 * Pluggable cold-storage sink invoked by the retention loop before terminal job rows
 * are deleted (DESIGN.md 18.7). Default binding is [Noop]; production deployments that
 * need long-term audit retention override with [FileArchivalSink] or their own S3/GCS
 * implementation.
 *
 * **Contract:** [archive] MUST be durable — returning normally implies the data is
 * persisted in cold storage. If it throws, the retention loop SKIPS the corresponding
 * DELETE, so the rows live another tick and the sink gets another shot. Idempotent
 * batches (some rows already in cold storage from a previous failed pass) are the sink's
 * responsibility — pick a key scheme (id + version, or `category/date/file:line`) that
 * tolerates re-writes.
 *
 * **Single sink, multiple categories.** `category` identifies the source bucket
 * (`"job.succeeded"`, `"job.failed"`, `"job.cancelled"` today; Phase 3 may add
 * `"outbox.published"` etc.). Implementations are encouraged to partition writes by
 * category so a single sink instance can serve many tables.
 */
public interface ArchivalSink {

    public suspend fun archive(category: String, batch: List<ArchivedJobRecord>)

    /** No-op default — terminal rows go straight to DELETE with no archival. */
    public object Noop : ArchivalSink {
        override suspend fun archive(category: String, batch: List<ArchivedJobRecord>): Unit = Unit
    }
}
