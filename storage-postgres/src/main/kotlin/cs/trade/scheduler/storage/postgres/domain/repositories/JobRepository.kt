package cs.trade.scheduler.storage.postgres.domain.repositories

import cs.trade.scheduler.shared.JobState
import cs.trade.scheduler.storage.postgres.domain.models.Job
import cs.trade.scheduler.storage.postgres.domain.models.JobListFilter
import cs.trade.scheduler.storage.postgres.domain.models.PagedResult
import kotlin.time.Duration
import kotlin.time.Instant
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * Read/write access to `job` rows. One responsibility — job lifecycle CRUD.
 *
 * Implementations live in `infrastructure/repositories/JobRepositoryImpl.kt` using Exposed.
 *
 * Per the "1 function repo ↔ 1 UseCase" rule (DESIGN.md section 3.3), each public method
 * here gets a wrapping UseCase in `:dashboard-server` domain.
 */
@OptIn(ExperimentalUuidApi::class)
public interface JobRepository {

    public suspend fun findById(id: Uuid): Job?

    /**
     * Paginated list for the dashboard. `page` is 0-based. Ordered by `updated_at` DESC
     * so most-recently-touched rows surface first. Returns `total` matching the same
     * filter (one extra COUNT) so the UI can render "showing N of total".
     */
    public suspend fun findAll(filter: JobListFilter, page: Int, size: Int): PagedResult<Job>

    /**
     * INSERT a new job, optionally with an idempotency key (DESIGN.md 17.4).
     * Returns the new Job, or the *existing* job if the idempotency key clashed with
     * an already-active row.
     */
    public suspend fun insert(job: Job): Job

    /**
     * Atomic pickup transition for this worker. Accepts a job in either ENQUEUED (fresh
     * enqueue) or AWAITING_RETRY (re-delivery after backoff) and flips it to PROCESSING.
     * `pending_deps = 0` is required; the row's `version` provides the CAS check.
     *
     * Side effects on the returned row:
     *  - `state = PROCESSING`, `version++`, `attempts++`
     *  - `locked_by = nodeId`, `locked_until = now + lockDurationMillis`
     *  - `started_at = now`, `updated_at = now`
     *
     * Returns `null` if the job:
     *  - doesn't exist,
     *  - isn't in ENQUEUED or AWAITING_RETRY (already taken / cancelled / done),
     *  - still has unresolved parents,
     *  - was won by another worker (CAS conflict).
     */
    public suspend fun pickup(jobId: Uuid, nodeId: String, lockDurationMillis: Long): Job?

    /**
     * PROCESSING → SUCCEEDED. Clears the lock, sets `duration_ms = now - started_at`,
     * bumps version. Returns true on update, false on optimistic-lock conflict.
     */
    public suspend fun markSucceeded(jobId: Uuid, expectedVersion: Int): Boolean

    /**
     * PROCESSING → FAILED (terminal — no more retries). Clears the lock, sets
     * `duration_ms`, bumps version. Retry decision lives one level up
     * ([cs.trade.scheduler.core.backend.handler.retry.RetryPolicy] in the worker pool).
     *
     * Optional [errorMsg] + [errorStack] land in the job_event audit row so the
     * dashboard timeline shows what threw.
     */
    public suspend fun markFailed(
        jobId: Uuid,
        expectedVersion: Int,
        errorMsg: String? = null,
        errorStack: String? = null,
    ): Boolean

    /**
     * PROCESSING → AWAITING_RETRY. Clears the lock, sets `scheduled_at = now + backoff`
     * (informational; actual re-delivery is driven by the outbox row inserted alongside
     * with `delay_ms = backoff`), bumps version. Caller is responsible for inserting the
     * matching outbox row in the same DB transaction.
     *
     * Optional [errorMsg] + [errorStack] land in the job_event audit row so the
     * dashboard timeline records what triggered the retry.
     */
    public suspend fun markForRetry(
        jobId: Uuid,
        expectedVersion: Int,
        backoff: Duration,
        errorMsg: String? = null,
        errorStack: String? = null,
    ): Boolean

    /**
     * Transition to terminal CANCELLED. Mirrors [markSucceeded] / [markFailed] — clears
     * the lock, sets `duration_ms = now - started_at`, bumps version. CAS-guarded.
     *
     * Optional [errorMsg] carries a `JobCancellationException` message or an admin reason.
     *
     * When [actor] is non-null (dashboard / admin-triggered cancel), the audit row uses
     * eventType `MANUAL_CANCELLED` and stamps `actor`. When null (handler-side
     * `JobCancellationException`), eventType is plain `CANCELLED`.
     */
    public suspend fun markCancelled(
        jobId: Uuid,
        expectedVersion: Int,
        errorMsg: String? = null,
        actor: String? = null,
    ): Boolean

    /**
     * Stamp `cancel_requested_at = at` and `cancel_requested_by = by` on a PROCESSING
     * row WITHOUT bumping version (so the heartbeat / markSucceeded / markFailed running
     * concurrently never see a CAS conflict from us). The UPDATE is scoped to
     * `WHERE id = ? AND state = 'PROCESSING'`, so it returns 0 rows once the worker
     * has already moved on.
     *
     * Multiple cancel requests are idempotent — last-writer-wins on `cancel_requested_by`.
     */
    public suspend fun requestCancellation(jobId: Uuid, by: String?, at: Instant): Boolean

    /**
     * DAG dep resolution: atomically decrement `pending_deps` on an AWAITING_DEPS child
     * and flip its state to ENQUEUED when the counter reaches 0. Returns the resulting
     * state classification — callers (FinalizeJobUseCase) use [DepDecrementResult.PROMOTED]
     * to decide whether to also insert an outbox row.
     *
     * State-scoped to AWAITING_DEPS, so a child that was meanwhile cancelled or already
     * promoted by another parent's resolution returns [DepDecrementResult.NOT_AWAITING]
     * and we leave it alone.
     */
    public suspend fun decrementPendingDeps(childId: Uuid): DepDecrementResult

    /**
     * Cascade-finalise an AWAITING_DEPS row to a terminal state without dep-counting.
     * Used by `FinalizeJobUseCase` when a parent's failure should propagate
     * (`OnFailure.PROPAGATE_FAILURE` → FAILED) or stop the branch
     * (`OnFailure.CANCEL_CHILD` → CANCELLED). Returns true if the row was AWAITING_DEPS
     * and got transitioned; false otherwise.
     */
    public suspend fun cascadeTerminalIfAwaiting(childId: Uuid, terminal: JobState): Boolean

    /**
     * Atomic state transition guarded by `WHERE id = :id AND version = :v`.
     * Returns true on UPDATE, false on conflict (optimistic-lock failure).
     */
    public suspend fun transitionState(
        id: Uuid,
        expectedVersion: Int,
        newState: JobState,
        lockedBy: String? = null,
        lockedUntilMillis: Long? = null,
    ): Boolean

    /** Bulk heartbeat: one UPDATE for all in-flight jobs of a worker (DESIGN.md 13.4). */
    public suspend fun extendLocks(nodeId: String, newLockedUntilMillis: Long): Int

    /** Safety-net query — orphaned PROCESSING rows whose lock expired. */
    public suspend fun findOrphaned(limit: Int): List<Job>

    /**
     * SELECT `state = SCHEDULED` rows whose `scheduled_at <= upperBound`, ordered by
     * `scheduled_at` ASC. Used by `FastForwardTask` to pull jobs about to enter the
     * fast-forward window. Empty list when nothing's due.
     */
    public suspend fun findScheduledDue(upperBound: Instant, limit: Int): List<Job>

    /**
     * Look up a row by [idempotencyKey] among non-terminal states only. Mirrors the
     * partial unique index `job_idempotency_key_active_idx` from `V1__initial_schema.sql`
     * — once the previous job for a key has hit a terminal state, that key is "free"
     * for reuse, and this lookup returns `null` so the caller knows to insert fresh.
     */
    public suspend fun findActiveByIdempotencyKey(idempotencyKey: String): Job?

    /**
     * DELETE rows in terminal [state] whose `updated_at < olderThan`. Bounded by
     * [batchSize] (PG generates a subquery `id IN (SELECT id FROM job WHERE ... LIMIT n)`).
     * Returns the number of rows deleted.
     *
     * CASCADE FKs handle `job_event`, `job_dependency`, `outbox`: deleting a job nukes
     * its dependent rows automatically (see V1__initial_schema.sql).
     */
    public suspend fun deleteTerminalOlderThan(state: JobState, olderThan: Instant, batchSize: Int): Int

    /**
     * SELECT terminal-state rows past their retention cutoff. Returns the actual `Job`
     * domain models — used by the archival pipeline (DESIGN.md 18.7) so the sink can
     * serialise them before [deleteByIdsInState] removes the rows.
     */
    public suspend fun findTerminalOlderThan(state: JobState, olderThan: Instant, batchSize: Int): List<Job>

    /**
     * DELETE rows whose ids are in [ids] AND state == [state]. The state guard protects
     * against the small TOCTOU window between [findTerminalOlderThan] and this call —
     * if a row was MANUAL_RETRY'd in between (terminal → AWAITING_RETRY), the row's
     * state no longer matches and the DELETE silently skips it. Returns the count of
     * rows actually deleted.
     */
    public suspend fun deleteByIdsInState(ids: Collection<Uuid>, state: JobState): Int

    /**
     * `SELECT state, COUNT(*) FROM job GROUP BY state`. Used by the dashboard stats
     * overview. States with zero rows are absent from the map (rely on `.getOrDefault`).
     */
    public suspend fun countByState(): Map<JobState, Long>

    /**
     * "How loaded is each queue right now?" — counts non-terminal rows per queue. Used by
     * the dashboard backpressure indicator (DESIGN.md 20.10) to decide whether a queue is
     * normal / elevated / overloaded.
     *
     * Non-terminal here means ENQUEUED + PROCESSING + AWAITING_RETRY + AWAITING_DEPS +
     * SCHEDULED — anything that's still in some form of flight. Returns a map keyed by
     * queue name (queues with zero non-terminal rows are absent — caller defaults to 0).
     *
     * One GROUP BY query against the `job_state_*` indexes — fast enough for a 15s poll.
     */
    public suspend fun countActiveByQueue(): Map<String, Long>

    /**
     * Bulk lookup of `payload_type` for a set of job ids. Used by the outbox publisher to
     * filter out paused payload types in one extra query per batch rather than per row.
     * Missing ids are simply absent from the returned map.
     */
    public suspend fun findPayloadTypesByIds(ids: Collection<Uuid>): Map<Uuid, String>

    /**
     * `SELECT DISTINCT payload_type FROM job ORDER BY payload_type LIMIT ?`. Used by the
     * dashboard /api/types endpoint to populate the pause-form's known-types dropdown.
     *
     * Uses the existing `job_payload_type_state_idx` for an index-only scan — stays
     * cheap on tables with millions of rows. Capped at [limit] to bound the response;
     * apps with > 1000 distinct types should filter client-side anyway.
     */
    public suspend fun findDistinctPayloadTypes(limit: Int = 1000): List<String>

    /**
     * Persist a handler-reported progress sample on a PROCESSING row. Single UPDATE,
     * no version CAS — progress writes are throttled and best-effort by design (the
     * handler is the source of truth), so contending with retries/heartbeats here
     * would cost more than the occasional dropped update.
     *
     * State-scoped to PROCESSING so we don't accidentally repaint a SUCCEEDED row's
     * progress if a handler reports late. Returns `true` if the row was PROCESSING
     * and got stamped.
     */
    public suspend fun setProgress(jobId: Uuid, progress: Float, msg: String?, at: Instant): Boolean

    /**
     * Operator-initiated RE-ROUTE (DESIGN.md 22.2). Updates `target_node` / `target_tag`
     * on a non-terminal row. The caller (Scheduler.reroute) then inserts a fresh outbox
     * row so the new routing key takes effect.
     *
     * CAS on (id, version, state NOT IN terminal). Returns true on success; false on
     * version conflict OR row already terminal. Records `MANUAL_REROUTE` event with
     * [actor]. Either target may be null but not both.
     */
    public suspend fun updateRouting(
        jobId: Uuid,
        expectedVersion: Int,
        targetNode: String?,
        targetTag: String?,
        actor: String?,
    ): Boolean

    /**
     * Operator-initiated MANUAL_RETRY (DESIGN.md 18.6). FAILED → ENQUEUED in one CAS
     * UPDATE: `attempts = 0`, clear lock fields, clear `cancel_requested_*` defensively,
     * bump version. Records a `MANUAL_RETRY` event with [actor].
     *
     * State-scoped to FAILED — other states return false (caller decides via [RetryResult]).
     * Returns true on success; false on either version mismatch or non-FAILED state.
     * Caller (Scheduler.retry) tells the two apart by re-reading the row.
     */
    public suspend fun manualRetry(jobId: Uuid, expectedVersion: Int, actor: String?): Boolean

    /**
     * Persist a derived rollup-progress sample (DAG variant 3). Unlike [setProgress], this
     * is NOT scoped to PROCESSING — a rollup parent can sit in AWAITING_DEPS while its
     * rollup children run, and we want the dashboard to reflect that. Still excludes
     * terminal states so retention-aged rows aren't repainted.
     *
     * No `msg` parameter — rollup progress is purely numeric (no per-step narrative).
     */
    public suspend fun setRollupProgress(jobId: Uuid, progress: Float, at: Instant): Boolean

    /**
     * Release a PROCESSING lock back to ENQUEUED without counting it as a retry attempt.
     * Used by the worker's pause second-line check (DESIGN.md 22.1): the row got picked
     * up before the pause set was refreshed, we want to put it back in line, not bump
     * its attempts (which would eat into max_attempts for an unrelated reason).
     *
     * CAS on (id, version, state=PROCESSING). Returns true if the lock was released.
     */
    public suspend fun releaseProcessingLock(jobId: Uuid, expectedVersion: Int): Boolean

    /** Outcome of [decrementPendingDeps]. See its KDoc for semantics. */
    public enum class DepDecrementResult {
        /** Counter went from N → N-1 with N > 1; row stays AWAITING_DEPS. */
        DECREMENTED,

        /** Counter hit 0; row flipped AWAITING_DEPS → ENQUEUED. Caller must insert an outbox row. */
        PROMOTED,

        /** Row was not in AWAITING_DEPS (cancelled / already promoted / missing). */
        NOT_AWAITING,
    }
}
