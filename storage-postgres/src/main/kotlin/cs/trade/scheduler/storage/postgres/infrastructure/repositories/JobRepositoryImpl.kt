@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package cs.trade.scheduler.storage.postgres.infrastructure.repositories

import cs.trade.scheduler.core.backend.events.EventBus
import cs.trade.scheduler.shared.JobPriority
import cs.trade.scheduler.shared.JobState
import cs.trade.scheduler.shared.OnFailure
import cs.trade.scheduler.shared.events.WebSocketEvent
import cs.trade.scheduler.storage.postgres.domain.models.Job
import cs.trade.scheduler.storage.postgres.domain.models.JobListFilter
import cs.trade.scheduler.storage.postgres.domain.models.NewJobEvent
import cs.trade.scheduler.storage.postgres.domain.models.PagedResult
import cs.trade.scheduler.storage.postgres.domain.repositories.JobEventRepository
import cs.trade.scheduler.storage.postgres.domain.repositories.JobRepository
import cs.trade.scheduler.storage.postgres.infrastructure.tables.JobDependencyTable
import cs.trade.scheduler.storage.postgres.infrastructure.tables.JobTable
import cs.trade.scheduler.storage.postgres.infrastructure.toKotlinTime
import cs.trade.scheduler.storage.postgres.infrastructure.toOffsetDateTimeUtc
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.greaterEq
import org.jetbrains.exposed.v1.core.less
import org.jetbrains.exposed.v1.core.lessEq
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.suspendTransaction
import org.jetbrains.exposed.v1.jdbc.update
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * Exposed-backed [JobRepository]. All public methods dispatch to `Dispatchers.IO` then
 * open a `suspendTransaction` so callers can compose with other suspend code without
 * blocking a Netty/Ktor worker thread. Nested calls (e.g. `Scheduler.enqueue` opens a
 * transaction and then calls `jobs.insert + outbox.insert` inside it) reuse the outer
 * transaction automatically — `suspendTransaction` picks up the current coroutine-context
 * transaction and joins instead of opening a new connection.
 *
 * Exposed 1.x `uuid()` returns `Column<kotlin.uuid.Uuid>` directly — no java.util.UUID
 * bridge conversion needed at the repository boundary.
 *
 * Skeleton: [transitionState], [extendLocks], [findOrphaned] remain `TODO` until the
 * retry / heartbeat / safety-net loops land.
 */
public class JobRepositoryImpl(
    private val database: Database,
    private val eventBus: EventBus = EventBus.NoOp,
    private val events: JobEventRepository? = null,
) : JobRepository {

    private fun emitStateChange(jobId: Uuid, from: JobState, to: JobState, queue: String) {
        eventBus.publish(
            WebSocketEvent.JobStateChanged(
                id = jobId.toString(),
                from = from,
                to = to,
                queue = queue,
                at = Clock.System.now(),
            ),
        )
    }

    // Append-only audit row. Same transaction as the state UPDATE (caller's
    // suspendTransaction wraps both) so a committed transition always has its event.
    // Swallows errors from the events repo so a broken audit log never blocks the
    // hot scheduling path; the WS event already covers the dashboard refresh path.
    private suspend fun recordEvent(
        jobId: Uuid,
        eventType: String,
        prev: JobState?,
        new: JobState?,
        actor: String? = null,
        errorMsg: String? = null,
        errorStack: String? = null,
    ) {
        val repo = events ?: return
        runCatching {
            repo.insert(
                NewJobEvent(
                    jobId = jobId,
                    eventType = eventType,
                    prevState = prev,
                    newState = new,
                    actor = actor,
                    errorMsg = errorMsg,
                    errorStack = errorStack,
                ),
            )
        }
    }

    override suspend fun findById(id: Uuid): Job? = withContext(Dispatchers.IO) {
        suspendTransaction(db = database) {
            JobTable.selectAll()
                .where { JobTable.id eq id }
                .firstOrNull()
                ?.toJob()
        }
    }

    override suspend fun findAll(
        filter: JobListFilter,
        page: Int,
        size: Int,
    ): PagedResult<Job> = withContext(Dispatchers.IO) {
        suspendTransaction(db = database) {
            // Build Op<Boolean> directly via top-level operators (Exposed 1.x: the
            // SqlExpressionBuilder receiver was deprecated in favour of free functions).
            val predicate: Op<Boolean> = buildList {
                if (!filter.states.isNullOrEmpty()) {
                    add(JobTable.state inList filter.states.map { it.name })
                }
                filter.queue?.let { add(JobTable.queue eq it) }
                filter.payloadType?.let { add(JobTable.payloadType eq it) }
                // DLQ predicate — column-vs-column comparison is supported via the
                // overloaded `greaterEq`/`less` operators on Exposed columns.
                filter.attemptsExhausted?.let { exhausted ->
                    add(
                        if (exhausted) JobTable.attempts greaterEq JobTable.maxAttempts
                        else JobTable.attempts less JobTable.maxAttempts,
                    )
                }
            }.reduceOrNull { acc, p -> acc and p } ?: Op.TRUE

            val total = JobTable.selectAll().where(predicate).count()

            val offset = page.toLong() * size.toLong()
            val items = JobTable.selectAll().where(predicate)
                .orderBy(JobTable.updatedAt to SortOrder.DESC)
                .limit(size)
                .offset(offset)
                .map { it.toJob() }

            PagedResult(items = items, total = total, page = page, size = size)
        }
    }

    override suspend fun insert(job: Job): Job = withContext(Dispatchers.IO) {
        suspendTransaction(db = database) {
            val now = Clock.System.now().toOffsetDateTimeUtc()
            JobTable.insert { row ->
                row[id] = job.id
                row[state] = job.state.name
                row[queue] = job.queue
                row[priority] = job.priority.value
                row[payloadType] = job.payloadType
                row[payloadJson] = job.payloadJson
                row[scheduledAt] = job.scheduledAt?.toOffsetDateTimeUtc()
                row[attempts] = job.attempts
                row[maxAttempts] = job.maxAttempts
                row[timeoutSeconds] = job.timeoutSeconds
                row[lockedBy] = job.lockedBy
                row[lockedUntil] = job.lockedUntil?.toOffsetDateTimeUtc()
                row[pendingDeps] = job.pendingDeps
                // For a fresh insert initial == current, unless caller explicitly set a
                // non-zero value (e.g. when restoring rows in tests). Both cases collapse
                // to "use whichever is bigger" which matches the natural intent.
                row[initialPendingDeps] = maxOf(job.initialPendingDeps, job.pendingDeps)
                row[version] = job.version
                row[idempotencyKey] = job.idempotencyKey
                row[targetNode] = job.targetNode
                row[targetTag] = job.targetTag
                row[progress] = job.progress
                row[progressMsg] = job.progressMsg
                row[progressUpdatedAt] = job.progressUpdatedAt?.toOffsetDateTimeUtc()
                row[startedAt] = job.startedAt?.toOffsetDateTimeUtc()
                row[durationMs] = job.durationMs
                row[cancelRequestedAt] = job.cancelRequestedAt?.toOffsetDateTimeUtc()
                row[cancelRequestedBy] = job.cancelRequestedBy
                row[contextJson] = job.contextJson
                row[createdAt] = now
                row[updatedAt] = now
            }
            job.copy(
                createdAt = now.toKotlinTime(),
                updatedAt = now.toKotlinTime(),
            )
        }
    }

    override suspend fun pickup(jobId: Uuid, nodeId: String, lockDurationMillis: Long): Job? =
        withContext(Dispatchers.IO) {
            suspendTransaction(db = database) {
                // Snapshot → CAS UPDATE → re-derive in memory (cheaper than a re-SELECT).
                val current = JobTable.selectAll()
                    .where { JobTable.id eq jobId }
                    .firstOrNull()
                    ?.toJob()
                    ?: return@suspendTransaction null

                // AWAITING_RETRY is treated the same as ENQUEUED on the worker side —
                // the row was scheduled for re-delivery via the outbox + delayed exchange
                // and is now eligible to run again.
                val pickable = current.state == JobState.ENQUEUED ||
                    current.state == JobState.AWAITING_RETRY
                if (!pickable || current.pendingDeps != 0) {
                    return@suspendTransaction null
                }

                val now = Clock.System.now()
                val lockedUntil = now + lockDurationMillis.milliseconds
                val nowOdt = now.toOffsetDateTimeUtc()

                val rows = JobTable.update({
                    (JobTable.id eq jobId) and (JobTable.version eq current.version)
                }) {
                    it[state] = JobState.PROCESSING.name
                    it[attempts] = current.attempts + 1
                    it[version] = current.version + 1
                    it[lockedBy] = nodeId
                    it[this.lockedUntil] = lockedUntil.toOffsetDateTimeUtc()
                    it[startedAt] = nowOdt
                    it[updatedAt] = nowOdt
                }
                if (rows != 1) return@suspendTransaction null

                recordEvent(jobId, "PICKED_UP", prev = current.state, new = JobState.PROCESSING, actor = nodeId)
                emitStateChange(jobId, current.state, JobState.PROCESSING, current.queue)
                current.copy(
                    state = JobState.PROCESSING,
                    attempts = current.attempts + 1,
                    version = current.version + 1,
                    lockedBy = nodeId,
                    lockedUntil = lockedUntil,
                    startedAt = now,
                    updatedAt = now,
                )
            }
        }

    override suspend fun markSucceeded(jobId: Uuid, expectedVersion: Int): Boolean =
        finishTerminal(jobId, expectedVersion, JobState.SUCCEEDED, errorMsg = null, errorStack = null, actor = null)

    override suspend fun markFailed(
        jobId: Uuid,
        expectedVersion: Int,
        errorMsg: String?,
        errorStack: String?,
    ): Boolean = finishTerminal(jobId, expectedVersion, JobState.FAILED, errorMsg, errorStack, actor = null)

    override suspend fun markCancelled(
        jobId: Uuid,
        expectedVersion: Int,
        errorMsg: String?,
        actor: String?,
    ): Boolean = finishTerminal(jobId, expectedVersion, JobState.CANCELLED, errorMsg, errorStack = null, actor = actor)

    override suspend fun decrementPendingDeps(childId: Uuid): JobRepository.DepDecrementResult =
        withContext(Dispatchers.IO) {
            suspendTransaction(db = database) {
                val current = JobTable.selectAll()
                    .where { JobTable.id eq childId }
                    .firstOrNull()
                    ?: return@suspendTransaction JobRepository.DepDecrementResult.NOT_AWAITING

                if (current[JobTable.state] != JobState.AWAITING_DEPS.name) {
                    return@suspendTransaction JobRepository.DepDecrementResult.NOT_AWAITING
                }

                val now = Clock.System.now().toOffsetDateTimeUtc()
                val newPending = (current[JobTable.pendingDeps] - 1).coerceAtLeast(0)
                val currentVersion = current[JobTable.version]
                val willPromote = newPending == 0

                // DAG dependency-progress (variant 1): while the child sits in AWAITING_DEPS,
                // expose (initial - remaining) / initial as its progress so the dashboard can
                // show "3 of 5 deps satisfied" as a 60% bar. On PROMOTED we reset to null —
                // execution hasn't started and the handler will overwrite via updateProgress.
                val initial = current[JobTable.initialPendingDeps]
                val derivedProgress: Float? = when {
                    willPromote -> null
                    initial <= 0 -> null  // legacy row without an initial — can't derive
                    else -> ((initial - newPending).toFloat() / initial.toFloat())
                        .coerceIn(0f, 1f)
                }

                val rows = JobTable.update({
                    (JobTable.id eq childId) and (JobTable.version eq currentVersion)
                }) {
                    it[pendingDeps] = newPending
                    it[version] = currentVersion + 1
                    if (willPromote) it[state] = JobState.ENQUEUED.name
                    it[progress] = derivedProgress
                    if (derivedProgress != null || willPromote) {
                        it[progressUpdatedAt] = now
                    }
                    it[updatedAt] = now
                }
                if (rows != 1) {
                    // Race: someone bumped version between our read and write — treat as
                    // "we didn't decrement". Caller can retry at the use-case level.
                    return@suspendTransaction JobRepository.DepDecrementResult.NOT_AWAITING
                }

                if (willPromote) {
                    recordEvent(childId, "PROMOTED", prev = JobState.AWAITING_DEPS, new = JobState.ENQUEUED)
                    emitStateChange(childId, JobState.AWAITING_DEPS, JobState.ENQUEUED, current[JobTable.queue])
                    JobRepository.DepDecrementResult.PROMOTED
                } else {
                    JobRepository.DepDecrementResult.DECREMENTED
                }
            }
        }

    override suspend fun setProgress(
        jobId: Uuid,
        progress: Float,
        msg: String?,
        at: Instant,
    ): Boolean = withContext(Dispatchers.IO) {
        suspendTransaction(db = database) {
            val atOdt = at.toOffsetDateTimeUtc()
            // Clamp into the documented [0, 1] band — defensive, since the handler API
            // is user-facing and they may pass percent (e.g. 50) by mistake.
            val clamped = progress.coerceIn(0f, 1f)
            // No version CAS — progress is best-effort. State-scoped to PROCESSING so a
            // late report after SUCCEEDED/CANCELLED can't repaint terminal rows.
            // Also NOT touching updated_at — heartbeat / pickup loops already keep that
            // fresh, and we don't want progress writes to look like state churn.
            val rows = JobTable.update({
                (JobTable.id eq jobId) and (JobTable.state eq JobState.PROCESSING.name)
            }) {
                it[JobTable.progress] = clamped
                it[JobTable.progressMsg] = msg
                it[JobTable.progressUpdatedAt] = atOdt
            }
            rows == 1
        }
    }

    override suspend fun releaseProcessingLock(
        jobId: Uuid,
        expectedVersion: Int,
    ): Boolean = withContext(Dispatchers.IO) {
        suspendTransaction(db = database) {
            val now = Clock.System.now().toOffsetDateTimeUtc()
            val rows = JobTable.update({
                (JobTable.id eq jobId) and
                    (JobTable.version eq expectedVersion) and
                    (JobTable.state eq JobState.PROCESSING.name)
            }) {
                it[state] = JobState.ENQUEUED.name
                it[lockedBy] = null
                it[lockedUntil] = null
                it[version] = expectedVersion + 1
                it[updatedAt] = now
            }
            val released = rows == 1
            if (released) {
                recordEvent(jobId, "LOCK_RELEASED", prev = JobState.PROCESSING, new = JobState.ENQUEUED)
                // No emitStateChange — back to ENQUEUED isn't a user-facing transition;
                // the worker that re-picks it up will emit PROCESSING again momentarily.
            }
            released
        }
    }

    override suspend fun updateRouting(
        jobId: Uuid,
        expectedVersion: Int,
        targetNode: String?,
        targetTag: String?,
        actor: String?,
    ): Boolean = withContext(Dispatchers.IO) {
        suspendTransaction(db = database) {
            val now = Clock.System.now().toOffsetDateTimeUtc()
            // CAS on version + non-terminal state — terminal rows have nothing to redirect.
            val rows = JobTable.update({
                (JobTable.id eq jobId) and
                    (JobTable.version eq expectedVersion) and
                    (JobTable.state inList ACTIVE_STATE_NAMES)
            }) {
                it[JobTable.targetNode] = targetNode
                it[JobTable.targetTag] = targetTag
                it[version] = expectedVersion + 1
                it[updatedAt] = now
            }
            val updated = rows == 1
            if (updated) {
                recordEvent(jobId, "MANUAL_REROUTE", prev = null, new = null, actor = actor)
            }
            updated
        }
    }

    override suspend fun manualRetry(
        jobId: Uuid,
        expectedVersion: Int,
        actor: String?,
    ): Boolean = withContext(Dispatchers.IO) {
        suspendTransaction(db = database) {
            val now = Clock.System.now().toOffsetDateTimeUtc()
            // CAS on (id, version, state=FAILED). Reset attempts to 0 so the retry gets
            // a fresh budget — an exhausted job would otherwise immediately re-fail.
            // Defensive lock/cancel clears keep future-proof if the state machine evolves.
            val rows = JobTable.update({
                (JobTable.id eq jobId) and
                    (JobTable.version eq expectedVersion) and
                    (JobTable.state eq JobState.FAILED.name)
            }) {
                it[state] = JobState.ENQUEUED.name
                it[attempts] = 0
                it[lockedBy] = null
                it[lockedUntil] = null
                it[cancelRequestedAt] = null
                it[cancelRequestedBy] = null
                it[version] = expectedVersion + 1
                it[updatedAt] = now
            }
            val retried = rows == 1
            if (retried) {
                recordEvent(
                    jobId,
                    "MANUAL_RETRY",
                    prev = JobState.FAILED,
                    new = JobState.ENQUEUED,
                    actor = actor,
                )
                val queueName = JobTable.selectAll()
                    .where { JobTable.id eq jobId }
                    .firstOrNull()
                    ?.get(JobTable.queue)
                if (queueName != null) {
                    emitStateChange(jobId, JobState.FAILED, JobState.ENQUEUED, queueName)
                }
            }
            retried
        }
    }

    override suspend fun setRollupProgress(
        jobId: Uuid,
        progress: Float,
        at: Instant,
    ): Boolean = withContext(Dispatchers.IO) {
        suspendTransaction(db = database) {
            val atOdt = at.toOffsetDateTimeUtc()
            val clamped = progress.coerceIn(0f, 1f)
            // Exclude terminal states to avoid repainting SUCCEEDED/FAILED/CANCELLED rows
            // — anything else (AWAITING_DEPS, ENQUEUED, PROCESSING) is fair game for a
            // rollup update. No version CAS — same best-effort semantics as setProgress.
            val rows = JobTable.update({
                (JobTable.id eq jobId) and (JobTable.state inList NON_TERMINAL_STATE_NAMES)
            }) {
                it[JobTable.progress] = clamped
                it[JobTable.progressUpdatedAt] = atOdt
            }
            rows == 1
        }
    }

    override suspend fun cascadeTerminalIfAwaiting(childId: Uuid, terminal: JobState): Boolean =
        withContext(Dispatchers.IO) {
            require(terminal.isTerminal) { "cascadeTerminalIfAwaiting requires a terminal state, got $terminal" }
            suspendTransaction(db = database) {
                val queue = JobTable.selectAll()
                    .where { JobTable.id eq childId }
                    .firstOrNull()
                    ?.get(JobTable.queue)
                val now = Clock.System.now().toOffsetDateTimeUtc()
                // No version CAS — state-CAS on AWAITING_DEPS is enough since nothing
                // else (pickup, retry, heartbeat) touches AWAITING_DEPS rows. scheduler.cancel
                // can, but that's also a terminal transition so it's still a noop here.
                val rows = JobTable.update({
                    (JobTable.id eq childId) and (JobTable.state eq JobState.AWAITING_DEPS.name)
                }) {
                    it[state] = terminal.name
                    it[updatedAt] = now
                }
                val updated = rows == 1
                if (updated && queue != null) {
                    recordEvent(childId, "CASCADED_${terminal.name}", prev = JobState.AWAITING_DEPS, new = terminal)
                    emitStateChange(childId, JobState.AWAITING_DEPS, terminal, queue)
                }
                updated
            }
        }

    override suspend fun cancelDescendantsAwaitingDeps(parentId: Uuid, by: String?): Int =
        withContext(Dispatchers.IO) {
            suspendTransaction(db = database) {
                // BFS over the DAG, one query per level. We can't reuse the existing
                // `cascadeTerminalIfAwaiting` because it doesn't stamp `cancel_requested_*`
                // (it's used for FAILED cascades where attribution is meaningless). Same
                // state-CAS pattern though: WHERE state = AWAITING_DEPS protects against
                // concurrent decrement-and-promote.
                //
                // Visited set defends against cycles even though the public API can't
                // build them — `enqueueAfter` always builds children pointing at already-
                // inserted parents, but a future bulk-import path might. Cheap to keep.
                val visited = HashSet<Uuid>()
                visited.add(parentId)               // never re-walk into the root parent
                val queue = ArrayDeque<Uuid>()
                queue.add(parentId)

                val nowOdt = Clock.System.now().toOffsetDateTimeUtc()
                var cancelled = 0

                while (queue.isNotEmpty()) {
                    val current = queue.removeFirst()
                    // All outbound edges from the current parent.
                    val edges = JobDependencyTable.selectAll()
                        .where { JobDependencyTable.parentId eq current }
                        .map {
                            it[JobDependencyTable.childId] to
                                OnFailure.valueOf(it[JobDependencyTable.onFailure])
                        }

                    for ((childId, onFailure) in edges) {
                        if (childId in visited) continue
                        visited.add(childId)

                        // IGNORE means the child explicitly opted out of parent-loss
                        // propagation — leave it alone (it'll just see one fewer dep
                        // resolved if/when FinalizeJobUseCase reaches it).
                        if (onFailure == OnFailure.IGNORE) continue

                        // Snapshot for the audit + WS event (we need the queue + prev
                        // version). State-scoped UPDATE skips anything not AWAITING_DEPS.
                        val snap = JobTable.selectAll()
                            .where { JobTable.id eq childId }
                            .firstOrNull()
                            ?: continue
                        val snapState = snap[JobTable.state]
                        val snapVersion = snap[JobTable.version]
                        val snapQueue = snap[JobTable.queue]
                        if (snapState != JobState.AWAITING_DEPS.name) {
                            // Already promoted / cancelled / terminal — leave it. But we
                            // still recurse: a SUCCEEDED child of root could itself have
                            // AWAITING_DEPS grandchildren that *we* should cancel. (Not
                            // actually possible — SUCCEEDED resolves via FinalizeJobUseCase
                            // — but the recursion costs nothing on dead branches.)
                            queue.add(childId)
                            continue
                        }

                        val rows = JobTable.update({
                            (JobTable.id eq childId) and
                                (JobTable.state eq JobState.AWAITING_DEPS.name) and
                                (JobTable.version eq snapVersion)
                        }) {
                            it[state] = JobState.CANCELLED.name
                            it[version] = snapVersion + 1
                            it[cancelRequestedAt] = nowOdt
                            it[cancelRequestedBy] = by
                            it[updatedAt] = nowOdt
                        }
                        if (rows == 1) {
                            cancelled += 1
                            // Audit + dashboard event. Distinct eventType so the timeline
                            // shows "cancelled because parent X was cancelled" — separate
                            // from a direct MANUAL_CANCELLED on this row.
                            recordEvent(
                                jobId = childId,
                                eventType = "CASCADE_CANCELLED",
                                prev = JobState.AWAITING_DEPS,
                                new = JobState.CANCELLED,
                                actor = by,
                            )
                            emitStateChange(childId, JobState.AWAITING_DEPS, JobState.CANCELLED, snapQueue)
                            // Recurse: descendants of THIS child may also be AWAITING_DEPS.
                            queue.add(childId)
                        }
                        // CAS lost (rows == 0) — race with concurrent finalize / cancel.
                        // Don't recurse; whoever won the CAS owns the descendant cascade.
                    }
                }
                cancelled
            }
        }

    override suspend fun requestCancellation(
        jobId: Uuid,
        by: String?,
        at: Instant,
    ): Boolean = withContext(Dispatchers.IO) {
        suspendTransaction(db = database) {
            val atOdt = at.toOffsetDateTimeUtc()
            // No version bump — marker semantics; LWW on cancel_requested_by. State-check
            // keeps us from stamping a row that already moved past PROCESSING.
            val rows = JobTable.update({
                (JobTable.id eq jobId) and (JobTable.state eq JobState.PROCESSING.name)
            }) {
                it[cancelRequestedAt] = atOdt
                it[cancelRequestedBy] = by
                it[updatedAt] = atOdt
            }
            val updated = rows == 1
            if (updated) {
                // Audit row even though state didn't change — gives the timeline a "user
                // asked for cancel at T" entry before the eventual MANUAL_CANCELLED that
                // lands when the handler honours the request.
                recordEvent(
                    jobId = jobId,
                    eventType = "CANCEL_REQUESTED",
                    prev = JobState.PROCESSING,
                    new = JobState.PROCESSING,
                    actor = by,
                )
            }
            updated
        }
    }

    private suspend fun finishTerminal(
        jobId: Uuid,
        expectedVersion: Int,
        terminal: JobState,
        errorMsg: String?,
        errorStack: String?,
        actor: String?,
    ): Boolean = withContext(Dispatchers.IO) {
        suspendTransaction(db = database) {
            val snap = JobTable.selectAll()
                .where { JobTable.id eq jobId }
                .firstOrNull()
            val startedAtMillis = snap?.get(JobTable.startedAt)?.toKotlinTime()?.toEpochMilliseconds()
            val priorState = snap?.get(JobTable.state)?.let { JobState.valueOf(it) }
            val queue = snap?.get(JobTable.queue)
            val now = Clock.System.now()
            val nowOdt = now.toOffsetDateTimeUtc()
            val durationMs: Long? = startedAtMillis?.let { now.toEpochMilliseconds() - it }

            val rows = JobTable.update({
                (JobTable.id eq jobId) and (JobTable.version eq expectedVersion)
            }) {
                it[state] = terminal.name
                it[version] = expectedVersion + 1
                it[lockedBy] = null
                it[lockedUntil] = null
                it[this.durationMs] = durationMs
                it[updatedAt] = nowOdt
            }
            val updated = rows == 1
            if (updated && priorState != null && queue != null) {
                // MANUAL_* prefix when an actor (dashboard user) triggered the transition.
                // Convention from DESIGN.md / JobEventDto KDoc.
                val eventType = if (actor != null) "MANUAL_${terminal.name}" else terminal.name
                recordEvent(
                    jobId = jobId,
                    eventType = eventType,
                    prev = priorState,
                    new = terminal,
                    actor = actor,
                    errorMsg = errorMsg,
                    errorStack = errorStack,
                )
                emitStateChange(jobId, priorState, terminal, queue)
            }
            updated
        }
    }

    override suspend fun markForRetry(
        jobId: Uuid,
        expectedVersion: Int,
        backoff: Duration,
        errorMsg: String?,
        errorStack: String?,
    ): Boolean = withContext(Dispatchers.IO) {
        suspendTransaction(db = database) {
            val queue = JobTable.selectAll()
                .where { JobTable.id eq jobId }
                .firstOrNull()
                ?.get(JobTable.queue)
            val now = Clock.System.now()
            val scheduledAtOdt = (now + backoff).toOffsetDateTimeUtc()
            val rows = JobTable.update({
                (JobTable.id eq jobId) and (JobTable.version eq expectedVersion)
            }) {
                it[state] = JobState.AWAITING_RETRY.name
                it[version] = expectedVersion + 1
                it[lockedBy] = null
                it[lockedUntil] = null
                it[scheduledAt] = scheduledAtOdt
                it[updatedAt] = now.toOffsetDateTimeUtc()
                // startedAt is overwritten by the next pickup; leave durationMs untouched
                // (only terminal states stamp it).
            }
            val updated = rows == 1
            if (updated && queue != null) {
                // From state is always PROCESSING here — markForRetry is the worker's
                // failure-with-retry path, only callable on a row it just owned.
                recordEvent(
                    jobId = jobId,
                    eventType = "RETRY",
                    prev = JobState.PROCESSING,
                    new = JobState.AWAITING_RETRY,
                    errorMsg = errorMsg,
                    errorStack = errorStack,
                )
                emitStateChange(jobId, JobState.PROCESSING, JobState.AWAITING_RETRY, queue)
            }
            updated
        }
    }

    override suspend fun transitionState(
        id: Uuid,
        expectedVersion: Int,
        newState: JobState,
        lockedBy: String?,
        lockedUntilMillis: Long?,
    ): Boolean = withContext(Dispatchers.IO) {
        suspendTransaction(db = database) {
            val snap = JobTable.selectAll()
                .where { JobTable.id eq id }
                .firstOrNull()
            val priorState = snap?.get(JobTable.state)?.let { JobState.valueOf(it) }
            val queue = snap?.get(JobTable.queue)
            val nowOdt = Clock.System.now().toOffsetDateTimeUtc()
            val lockedUntilOdt = lockedUntilMillis?.let {
                java.time.Instant.ofEpochMilli(it).atOffset(java.time.ZoneOffset.UTC)
            }
            val rows = JobTable.update({
                (JobTable.id eq id) and (JobTable.version eq expectedVersion)
            }) {
                it[state] = newState.name
                it[version] = expectedVersion + 1
                it[this.lockedBy] = lockedBy
                it[this.lockedUntil] = lockedUntilOdt
                it[updatedAt] = nowOdt
            }
            val updated = rows == 1
            if (updated && priorState != null && queue != null && priorState != newState) {
                recordEvent(id, "TRANSITION", prev = priorState, new = newState)
                emitStateChange(id, priorState, newState, queue)
            }
            updated
        }
    }

    override suspend fun extendLocks(nodeId: String, newLockedUntilMillis: Long): Int =
        withContext(Dispatchers.IO) {
            suspendTransaction(db = database) {
                // Heartbeat must NOT touch `version` — otherwise it would race with
                // every markSucceeded/markFailed/markForRetry that's running on the
                // same worker. lockedUntil + updatedAt only.
                val nowOdt = Clock.System.now().toOffsetDateTimeUtc()
                val lockedUntilOdt = java.time.Instant.ofEpochMilli(newLockedUntilMillis)
                    .atOffset(java.time.ZoneOffset.UTC)
                JobTable.update({
                    (JobTable.lockedBy eq nodeId) and (JobTable.state eq JobState.PROCESSING.name)
                }) {
                    it[lockedUntil] = lockedUntilOdt
                    it[updatedAt] = nowOdt
                }
            }
        }

    override suspend fun findOrphaned(limit: Int): List<Job> = withContext(Dispatchers.IO) {
        suspendTransaction(db = database) {
            val nowOdt = Clock.System.now().toOffsetDateTimeUtc()
            JobTable.selectAll()
                .where {
                    (JobTable.state eq JobState.PROCESSING.name) and
                        (JobTable.lockedUntil less nowOdt)
                }
                .orderBy(JobTable.lockedUntil to SortOrder.ASC)
                .limit(limit)
                .map { it.toJob() }
        }
    }

    override suspend fun deleteTerminalOlderThan(
        state: JobState,
        olderThan: Instant,
        batchSize: Int,
    ): Int {
        require(state.isTerminal) { "deleteTerminalOlderThan requires a terminal state, got $state" }
        return withContext(Dispatchers.IO) {
            suspendTransaction(db = database) {
                val thresholdOdt = olderThan.toOffsetDateTimeUtc()
                // PG doesn't allow `DELETE ... LIMIT N` — Exposed 1.x raises
                // UnsupportedByDialectException. Standard fix: SELECT-ids-LIMIT-N then
                // DELETE WHERE id IN (…). FK CASCADE on `job` handles job_event,
                // job_dependency, outbox children (V1__initial_schema.sql).
                //
                // The archival path uses `findTerminalOlderThan` + `deleteByIdsInState`
                // (so the sink sees the row before DELETE — DESIGN.md 18.7). This method
                // stays as a no-archival shortcut for callers that don't need a sink hook.
                val ids = JobTable
                    .select(JobTable.id)
                    .where { (JobTable.state eq state.name) and (JobTable.updatedAt less thresholdOdt) }
                    .orderBy(JobTable.updatedAt to SortOrder.ASC)
                    .limit(batchSize)
                    .map { it[JobTable.id] }
                if (ids.isEmpty()) 0
                else JobTable.deleteWhere {
                    (JobTable.id inList ids) and (JobTable.state eq state.name)
                }
            }
        }
    }

    override suspend fun findTerminalOlderThan(
        state: JobState,
        olderThan: Instant,
        batchSize: Int,
    ): List<Job> {
        require(state.isTerminal) { "findTerminalOlderThan requires a terminal state, got $state" }
        return withContext(Dispatchers.IO) {
            suspendTransaction(db = database) {
                val cutoff = olderThan.toOffsetDateTimeUtc()
                JobTable.selectAll()
                    .where { (JobTable.state eq state.name) and (JobTable.updatedAt less cutoff) }
                    .orderBy(JobTable.updatedAt to SortOrder.ASC)
                    .limit(batchSize)
                    .map { it.toJob() }
            }
        }
    }

    override suspend fun deleteByIdsInState(ids: Collection<Uuid>, state: JobState): Int {
        if (ids.isEmpty()) return 0
        return withContext(Dispatchers.IO) {
            suspendTransaction(db = database) {
                JobTable.deleteWhere {
                    (JobTable.id inList ids) and (JobTable.state eq state.name)
                }
            }
        }
    }

    override suspend fun countByState(): Map<JobState, Long> = withContext(Dispatchers.IO) {
        suspendTransaction(db = database) {
            // One COUNT per state — 8 cheap queries instead of one GROUP BY. Exposed 1.x
            // changed the slice-projection API and per-state counts use only the
            // `job_state_*` indexes; the dashboard hits this maybe once per minute.
            JobState.entries.associateWith { state ->
                JobTable.selectAll().where { JobTable.state eq state.name }.count()
            }
        }
    }

    override suspend fun countActiveByQueue(): Map<String, Long> = withContext(Dispatchers.IO) {
        suspendTransaction(db = database) {
            // GROUP BY queue over non-terminal rows. Exposed 1.x renamed the count()
            // aggregate column-builder, and the cleanest robust path is a tiny raw SQL
            // via `exec` — JobState names are an enum (no SQL injection surface). The
            // dashboard polls this every 15s; the lack of a composite (state, queue)
            // index is fine — the partial state-only index narrows the scan plenty.
            val nonTerminal = JobState.entries
                .filterNot { it.isTerminal }
                .joinToString(",") { "'${it.name}'" }
            val sql = "SELECT queue, COUNT(*) AS cnt FROM job WHERE state IN ($nonTerminal) GROUP BY queue"
            val result = mutableMapOf<String, Long>()
            exec(sql) { rs ->
                while (rs.next()) {
                    result[rs.getString("queue")] = rs.getLong("cnt")
                }
            }
            result
        }
    }

    override suspend fun findScheduledDue(upperBound: Instant, limit: Int): List<Job> =
        withContext(Dispatchers.IO) {
            suspendTransaction(db = database) {
                val upperOdt = upperBound.toOffsetDateTimeUtc()
                JobTable.selectAll()
                    .where {
                        (JobTable.state eq JobState.SCHEDULED.name) and
                            (JobTable.scheduledAt lessEq upperOdt)
                    }
                    .orderBy(JobTable.scheduledAt to SortOrder.ASC)
                    .limit(limit)
                    .map { it.toJob() }
            }
        }

    override suspend fun findActiveByIdempotencyKey(idempotencyKey: String): Job? =
        withContext(Dispatchers.IO) {
            suspendTransaction(db = database) {
                JobTable.selectAll()
                    .where {
                        (JobTable.idempotencyKey eq idempotencyKey) and
                            (JobTable.state inList ACTIVE_STATE_NAMES)
                    }
                    .limit(1)
                    .firstOrNull()
                    ?.toJob()
            }
        }

    override suspend fun findDistinctPayloadTypes(limit: Int): List<String> =
        withContext(Dispatchers.IO) {
            suspendTransaction(db = database) {
                // Index-only scan over job_payload_type_state_idx — DISTINCT + ORDER BY +
                // LIMIT all served from the index without touching heap pages.
                JobTable
                    .select(JobTable.payloadType)
                    .withDistinct()
                    .orderBy(JobTable.payloadType to SortOrder.ASC)
                    .limit(limit)
                    .map { it[JobTable.payloadType] }
            }
        }

    override suspend fun findPayloadTypesByIds(ids: Collection<Uuid>): Map<Uuid, String> {
        if (ids.isEmpty()) return emptyMap()
        return withContext(Dispatchers.IO) {
            suspendTransaction(db = database) {
                JobTable.selectAll()
                    .where { JobTable.id inList ids }
                    .associate { it[JobTable.id].value to it[JobTable.payloadType] }
            }
        }
    }

    private companion object {
        // Mirrors the partial unique index predicate in V1__initial_schema.sql.
        val ACTIVE_STATE_NAMES: List<String> = JobState.entries
            .filterNot { it.isTerminal }
            .map { it.name }
        // Same set, separate name for read-site clarity in setRollupProgress.
        val NON_TERMINAL_STATE_NAMES: List<String> = ACTIVE_STATE_NAMES
    }
}

private fun ResultRow.toJob(): Job = Job(
    id = this[JobTable.id].value,
    state = JobState.valueOf(this[JobTable.state]),
    queue = this[JobTable.queue],
    priority = JobPriority(this[JobTable.priority]),
    payloadType = this[JobTable.payloadType],
    payloadJson = this[JobTable.payloadJson],
    scheduledAt = this[JobTable.scheduledAt]?.toKotlinTime(),
    attempts = this[JobTable.attempts],
    maxAttempts = this[JobTable.maxAttempts],
    timeoutSeconds = this[JobTable.timeoutSeconds],
    lockedBy = this[JobTable.lockedBy],
    lockedUntil = this[JobTable.lockedUntil]?.toKotlinTime(),
    pendingDeps = this[JobTable.pendingDeps],
    initialPendingDeps = this[JobTable.initialPendingDeps],
    version = this[JobTable.version],
    idempotencyKey = this[JobTable.idempotencyKey],
    targetNode = this[JobTable.targetNode],
    targetTag = this[JobTable.targetTag],
    progress = this[JobTable.progress],
    progressMsg = this[JobTable.progressMsg],
    progressUpdatedAt = this[JobTable.progressUpdatedAt]?.toKotlinTime(),
    startedAt = this[JobTable.startedAt]?.toKotlinTime(),
    durationMs = this[JobTable.durationMs],
    cancelRequestedAt = this[JobTable.cancelRequestedAt]?.toKotlinTime(),
    cancelRequestedBy = this[JobTable.cancelRequestedBy],
    contextJson = this[JobTable.contextJson],
    createdAt = this[JobTable.createdAt].toKotlinTime(),
    updatedAt = this[JobTable.updatedAt].toKotlinTime(),
)
