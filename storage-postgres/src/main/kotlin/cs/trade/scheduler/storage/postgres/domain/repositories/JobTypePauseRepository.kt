package cs.trade.scheduler.storage.postgres.domain.repositories

import cs.trade.scheduler.storage.postgres.domain.models.JobTypePauseRow
import kotlin.time.Instant

/**
 * Pause/unpause job execution by `payload_type`. See DESIGN.md 22.1.
 *
 * Semantics: when a type is paused, two enforcement points block its jobs:
 *  - [PublishOutboxBatchUseCase] in engine-infra skips outbox rows for paused types
 *    (they accumulate with `published_at IS NULL` until unpause)
 *  - [WorkerPool] in engine-worker does a second-line check after pickup (race window:
 *    pause happened after the row was already in Rabbit) and re-defers the job
 *
 * Unpause = catch-up: no special action — the next outbox tick sees the type is no
 * longer paused and publishes everything that accumulated.
 */
public interface JobTypePauseRepository {

    /**
     * INSERT or UPDATE — pausing a type that's already paused refreshes [pausedBy] /
     * [reason] / [pausedSince] to the new caller's values (last-writer-wins).
     */
    public suspend fun pause(
        payloadType: String,
        pausedBy: String,
        reason: String?,
        pausedSince: Instant,
    )

    /** DELETE — idempotent. Returns true if a row existed. */
    public suspend fun unpause(payloadType: String): Boolean

    /** Fast existence check for the enforcement hot path. */
    public suspend fun isPaused(payloadType: String): Boolean

    /** Set of currently paused payload types — used to filter outbox batches. */
    public suspend fun findPausedTypes(): Set<String>

    /** Full row list for the dashboard /api/types/pauses endpoint. */
    public suspend fun findAll(): List<JobTypePauseRow>
}
