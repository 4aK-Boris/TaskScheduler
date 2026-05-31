package cs.trade.scheduler.storage.postgres.infrastructure.scheduler

import cs.trade.scheduler.core.backend.EnqueueOptions
import cs.trade.scheduler.core.backend.RecurringDefinition
import cs.trade.scheduler.core.backend.Scheduler
import cs.trade.scheduler.core.backend.SchedulerCoreConfig
import cs.trade.scheduler.core.backend.archival.ArchivalSink
import cs.trade.scheduler.core.backend.context.ContextCapture
import cs.trade.scheduler.core.backend.context.ContextSnapshot
import cs.trade.scheduler.core.backend.cron.CronExpr
import cs.trade.scheduler.core.backend.events.EventBus
import cs.trade.scheduler.core.backend.functionref.FunctionRefEnqueuer
import cs.trade.scheduler.shared.functionref.FunctionRefPayload
import cs.trade.scheduler.core.backend.handler.Job
import kotlin.reflect.KFunction
import cs.trade.scheduler.shared.CancelResult
import cs.trade.scheduler.shared.DeleteResult
import cs.trade.scheduler.shared.JobPriority
import cs.trade.scheduler.shared.JobState
import cs.trade.scheduler.shared.RerouteResult
import cs.trade.scheduler.shared.RetryMode
import cs.trade.scheduler.shared.RetryResult
import cs.trade.scheduler.shared.events.WebSocketEvent
import cs.trade.scheduler.storage.postgres.domain.StorageProvider
import cs.trade.scheduler.storage.postgres.domain.models.NewJobEvent
import cs.trade.scheduler.storage.postgres.domain.models.NewOutboxEntry
import cs.trade.scheduler.storage.postgres.domain.models.Job as JobRow
import cs.trade.scheduler.storage.postgres.domain.models.RecurringJobRow
import cs.trade.scheduler.storage.postgres.domain.repositories.JobEventRepository
import cs.trade.scheduler.storage.postgres.infrastructure.archival.toArchivedRecord
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.serializer
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.transactions.suspendTransaction
import kotlin.reflect.full.starProjectedType
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Instant
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * MVP-stage [Scheduler] implementation. `enqueue` + `scheduleAt` are wired; the rest
 * of the API surface throws `TODO()` until the corresponding flows land
 * (chain → 8.3, enqueueOnce → 17.4, recurring → 7.5).
 *
 * `enqueue` and the direct branch of `scheduleAt` (delay within
 * [SchedulerCoreConfig.fastForwardWindow]) both run ONE suspended Exposed transaction
 * that:
 *   1. inserts the job row,
 *   2. inserts the outbox row pointing at it.
 *
 * The deferred branch of `scheduleAt` (delay beyond the fast-forward window) writes
 * **only** the job row in state SCHEDULED; the [FastForwardTask] in `:engine-infra`
 * promotes it later when its `scheduled_at` falls within the window.
 *
 * Payload serialisation goes through `kotlin.reflect`-based `serializer(KType)` — the
 * concrete `@Serializable` class is taken from `job::class`. Phase 2 KSP plugin will
 * replace this with compile-time-resolved serializers.
 *
 * Lives in `:storage-postgres` (not `:core:backend`) so that core stays free of
 * storage-engine deps. Registered by [schedulerPostgresModule].
 */
@OptIn(ExperimentalUuidApi::class)
public class DefaultScheduler(
    private val storage: StorageProvider,
    private val database: Database,
    private val config: SchedulerCoreConfig,
    private val eventBus: EventBus = EventBus.NoOp,
    private val events: JobEventRepository? = null,
    private val contextCapture: ContextCapture? = null,
    private val archivalSink: ArchivalSink = ArchivalSink.Noop,
    /**
     * Resolves Koin bindings for the function-ref API (DESIGN.md 21.5) so we can fail
     * fast on multi-binding-without-qualifier at enqueue rather than at execute.
     *
     * Defaults to a lookup that does nothing — the constructor stays usable from tests
     * that don't exercise the function-ref path. Production wiring (in
     * `schedulerPostgresModule`) injects a real Koin-backed resolver.
     */
    private val functionRefBindingResolver: FunctionRefBindingResolver = FunctionRefBindingResolver.AlwaysOk,
) : Scheduler {

    private val json: Json get() = config.json

    override suspend fun start() {
        // No-op for MVP — worker pool / loops are owned by their own modules.
    }

    override suspend fun stop(timeout: Duration) {
        // No-op for MVP.
    }

    override suspend fun enqueue(job: Job, options: EnqueueOptions): Uuid {
        val params = buildParams(job, options)
        val row = newJobRow(params, state = JobState.ENQUEUED, scheduledAt = null)

        withContext(Dispatchers.IO) {
            suspendTransaction(db = database) {
                storage.jobs.insert(row)
                storage.outbox.insert(
                    NewOutboxEntry(
                        jobId = params.jobId,
                        routingKey = params.routingKey,
                        priority = params.priority,
                        delayMs = 0,
                    ),
                )
                recordCreated(params.jobId, newState = JobState.ENQUEUED)
            }
        }
        emitCreated(params)
        return params.jobId
    }

    override suspend fun scheduleAt(job: Job, at: Instant, options: EnqueueOptions): Uuid {
        val params = buildParams(job, options)
        val now = Clock.System.now()
        val delay = (at - now).coerceAtLeast(Duration.ZERO)
        val withinWindow = delay <= config.fastForwardWindow

        val state = if (withinWindow) JobState.ENQUEUED else JobState.SCHEDULED
        val row = newJobRow(params, state = state, scheduledAt = at)

        withContext(Dispatchers.IO) {
            suspendTransaction(db = database) {
                storage.jobs.insert(row)
                if (withinWindow) {
                    storage.outbox.insert(
                        NewOutboxEntry(
                            jobId = params.jobId,
                            routingKey = params.routingKey,
                            priority = params.priority,
                            delayMs = delay.inWholeMilliseconds,
                        ),
                    )
                }
                // else: no outbox row. FastForwardTask will INSERT one when this row's
                // scheduled_at falls inside the window.
                recordCreated(params.jobId, newState = state)
            }
        }
        emitCreated(params)
        return params.jobId
    }

    override suspend fun enqueueOnce(key: String, job: Job, options: EnqueueOptions): Uuid {
        // Fast path: check first. Saves the cost of a failed INSERT under hot keys
        // (web app retrying an already-active operation). Doesn't close the race —
        // the catch below handles the case where another caller wins between SELECT
        // and INSERT.
        storage.jobs.findActiveByIdempotencyKey(key)?.let { return it.id }

        val params = buildParams(job, options)
        val row = newJobRow(params, state = JobState.ENQUEUED, scheduledAt = null)
            .copy(idempotencyKey = key)

        val attempt = runCatching {
            withContext(Dispatchers.IO) {
                suspendTransaction(db = database) {
                    storage.jobs.insert(row)
                    storage.outbox.insert(
                        NewOutboxEntry(
                            jobId = params.jobId,
                            routingKey = params.routingKey,
                            priority = params.priority,
                            delayMs = 0,
                        ),
                    )
                    recordCreated(params.jobId, newState = JobState.ENQUEUED)
                }
            }
        }

        return attempt.fold(
            onSuccess = {
                emitCreated(params)
                params.jobId
            },
            onFailure = { t ->
                if (isUniqueViolation(t)) {
                    // Lost the race: someone else's INSERT got there first under the
                    // partial unique index on (idempotency_key) WHERE state is active.
                    // Re-read and return their id.
                    storage.jobs.findActiveByIdempotencyKey(key)?.id
                        ?: throw IllegalStateException(
                            "Unique violation on idempotency_key=$key but no active row visible afterwards",
                            t,
                        )
                } else {
                    throw t
                }
            },
        )
    }

    private fun emitCreated(params: EnqueueParams) {
        eventBus.publish(
            WebSocketEvent.JobCreated(
                id = params.jobId.toString(),
                queue = params.queue,
                type = params.payloadType,
                at = Clock.System.now(),
            ),
        )
    }

    // Audit log entry for "this job started life in [newState]". Called inside the same
    // suspendTransaction that inserts the job row so the timeline stays atomic with the
    // row's existence. Best-effort if events repo wasn't wired.
    private suspend fun recordCreated(jobId: Uuid, newState: JobState) {
        val repo = events ?: return
        runCatching {
            repo.insert(NewJobEvent(jobId = jobId, eventType = "CREATED", newState = newState))
        }
    }

    private fun isUniqueViolation(t: Throwable): Boolean {
        var cur: Throwable? = t
        while (cur != null) {
            if (cur is java.sql.SQLException && cur.sqlState == PG_UNIQUE_VIOLATION) return true
            cur = cur.cause
        }
        return false
    }

    private companion object {
        // Postgres SQLSTATE for unique_violation — DESIGN.md 17.4 / pg docs.
        const val PG_UNIQUE_VIOLATION = "23505"
        const val CANCEL_ATTEMPTS = 3
    }

    override suspend fun chain(vararg jobs: Job, priority: Int?): List<Uuid> {
        require(jobs.isNotEmpty()) { "chain() requires at least one job" }
        // Length guardrail (DESIGN.md 22.10) — caps a runaway chain before any DB write.
        require(jobs.size <= config.maxChainLength) {
            "chain length ${jobs.size} exceeds maxChainLength=${config.maxChainLength} — " +
                "raise SchedulerCoreConfig.maxChainLength if it's intentional"
        }
        // A non-null `priority` pins every step; null falls through to the per-step
        // handler/queue/global default (DESIGN.md 19.3) so the no-arg chain() is unchanged.
        val options = if (priority != null) EnqueueOptions(priority = priority) else EnqueueOptions()
        val ids = mutableListOf<Uuid>()
        for ((index, j) in jobs.withIndex()) {
            val id = if (index == 0) {
                enqueue(j, options)
            } else {
                enqueueAfter(j, waitFor = listOf(ids.last()), options = options)
            }
            ids.add(id)
        }
        return ids
    }

    override suspend fun enqueueAfter(
        job: Job,
        waitFor: List<Uuid>,
        options: EnqueueOptions,
    ): Uuid {
        require(waitFor.isNotEmpty()) {
            "enqueueAfter requires at least one parent — use enqueue() for no-dep jobs"
        }
        // Dedup parents up front (DESIGN.md 22.10). `after(a, a)` must count as ONE
        // dependency: a naive `pending_deps = waitFor.size` would leave the child stuck
        // at 2 while only one parent ever finalises, and the second edge INSERT would hit
        // the composite PK. `.distinct()` keeps both the counter and the edge set honest;
        // insertIgnore in the repo is the belt-and-suspenders backstop.
        val parents = waitFor.distinct()
        // Fan-in guardrail (DESIGN.md 22.10): cap how many DISTINCT parents one job waits on.
        // Checked after distinct() (so `after(a, a)` counts as one) and before any DB write,
        // so an over-wide barrier fails fast with no partial graph left behind.
        require(parents.size <= config.maxDagFanIn) {
            "enqueueAfter fan-in ${parents.size} exceeds maxDagFanIn=${config.maxDagFanIn} — " +
                "a barrier on this many parents is almost always a bug; raise " +
                "SchedulerCoreConfig.maxDagFanIn if it's intentional"
        }
        // Priority inheritance (DESIGN.md 19.7 Phase 3). When explicit `options.priority`
        // is null AND `inheritPriorityFromParents = true`, look up parents and take the
        // max priority. Skipped if parents have been retention-cleaned by the time we
        // look (returns 0 default for missing rows — parents gone means the job is
        // racy anyway and the priority detail isn't load-bearing).
        val effectiveOptions = if (options.priority == null && options.inheritPriorityFromParents) {
            val parentMaxPriority = withContext(Dispatchers.IO) {
                suspendTransaction(db = database) {
                    parents.maxOf { parentId ->
                        storage.jobs.findById(parentId)?.priority?.value ?: 0
                    }
                }
            }
            options.copy(priority = parentMaxPriority)
        } else {
            options
        }
        val params = buildParams(job, effectiveOptions)
        // pending_deps starts at the DISTINCT parent count; the child sits AWAITING_DEPS
        // with no outbox row until parents finish and FinalizeJobUseCase promotes it.
        val row = newJobRow(params, state = JobState.AWAITING_DEPS, scheduledAt = null)
            .copy(pendingDeps = parents.size)

        withContext(Dispatchers.IO) {
            suspendTransaction(db = database) {
                storage.jobs.insert(row)
                for (parentId in parents) {
                    storage.jobDependencies.insert(
                        parentId = parentId,
                        childId = params.jobId,
                        onFailure = options.onParentFailure,
                    )
                }
                recordCreated(params.jobId, newState = JobState.AWAITING_DEPS)
            }
        }
        emitCreated(params)
        return params.jobId
    }

    override suspend fun recurring(definition: RecurringDefinition) {
        val now = Clock.System.now()
        // Validate cron expression up-front + compute initial next trigger.
        val nextTrigger = CronExpr.nextAfter(
            expression = definition.cron,
            reference = now,
            timezone = definition.timezone,
        )

        val payloadType = definition.job::class.qualifiedName
            ?: error("Recurring job payload class must have a qualifiedName")
        val payloadJson = json.encodeToString(
            serializer(definition.job::class.starProjectedType),
            definition.job,
        )

        storage.recurringJobs.upsert(
            RecurringJobRow(
                id = definition.id,
                cron = definition.cron,
                timezone = definition.timezone,
                misfirePolicy = definition.misfirePolicy,
                queue = definition.queue,
                priority = definition.priority,
                targetNode = definition.targetNode,
                targetTag = definition.targetTag,
                payloadType = payloadType,
                payloadJson = payloadJson,
                lastTriggeredAt = null,         // upsert preserves existing if row already there
                nextTriggerAt = nextTrigger,
                enabled = true,                 // upsert preserves existing if row already there
                timeoutSeconds = definition.timeout?.inWholeSeconds?.toInt(),
            ),
        )
    }

    override suspend fun cancel(jobId: Uuid, by: String?): CancelResult {
        // Bounded retry — if CAS keeps losing, the row is being concurrently mutated;
        // we degrade to "report whatever state we now see".
        repeat(CANCEL_ATTEMPTS) {
            val current = storage.jobs.findById(jobId) ?: return CancelResult.NOT_FOUND
            if (current.state.isTerminal) {
                return if (current.state == JobState.CANCELLED) CancelResult.CANCELLED
                else CancelResult.ALREADY_TERMINAL
            }

            val won = if (current.state == JobState.PROCESSING) {
                if (storage.jobs.requestCancellation(jobId, by, Clock.System.now())) {
                    return CancelResult.CANCEL_REQUESTED
                }
                false
            } else {
                if (storage.jobs.markCancelled(jobId, current.version, errorMsg = null, actor = by)) {
                    // DESIGN.md 8.4 — descendants in AWAITING_DEPS have nothing left
                    // to wait for and would otherwise sit forever. Propagate the cancel
                    // along PROPAGATE_FAILURE / CANCEL_CHILD edges (IGNORE edges opt out).
                    // Best-effort: a failure here is logged but doesn't downgrade the
                    // primary CancelResult — the user-facing cancel already succeeded.
                    runCatching {
                        storage.jobs.cancelDescendantsAwaitingDeps(jobId, by)
                    }.onFailure { t ->
                        // No logger framework wired in this class — System.err is fine for
                        // the rare admin-cancel cascade failure. Cluster monitoring picks
                        // up the orphaned AWAITING_DEPS rows via the dashboard.
                        System.err.println(
                            "cancelDescendantsAwaitingDeps failed for parent=$jobId by=$by: ${t.message}",
                        )
                    }
                    return CancelResult.CANCELLED
                }
                false
            }
            check(!won) { "unreachable" }
            // CAS lost — re-read and try once more.
        }

        // Persistent race — return based on final visible state.
        val finalRow = storage.jobs.findById(jobId)
        return when {
            finalRow == null -> CancelResult.NOT_FOUND
            finalRow.state == JobState.CANCELLED -> CancelResult.CANCELLED
            finalRow.state.isTerminal -> CancelResult.ALREADY_TERMINAL
            else -> CancelResult.CANCEL_REQUESTED
        }
    }

    override suspend fun delete(jobId: Uuid, by: String?): DeleteResult {
        val current = storage.jobs.findById(jobId) ?: return DeleteResult.NOT_FOUND
        if (!current.state.isTerminal) return DeleteResult.NOT_TERMINAL

        // Archive BEFORE the DELETE — if the sink throws, the row stays put and the
        // operator gets the error (rather than a silently-lost row). Same contract as
        // RetentionCleanupBatchUseCase. Reuse the same `job.<state>` category naming
        // so cold storage doesn't need a separate "manual_delete" bucket.
        val category = "job.${current.state.name.lowercase()}"
        archivalSink.archive(category, listOf(current.toArchivedRecord()))

        // State-guarded DELETE — between findById and now, the row could have been
        // resurrected by MANUAL_RETRY (FAILED → ENQUEUED). The guard skips it then;
        // operator sees NOT_TERMINAL on the re-check below. Over-archive (sink got
        // a copy of a row that's still alive) is acceptable.
        val deleted = storage.jobs.deleteByIdsInState(listOf(jobId), current.state)
        if (deleted != 1) {
            // Row state changed between the read and the delete — figure out what to
            // tell the caller from the current visible state.
            val now = storage.jobs.findById(jobId)
            return when {
                now == null -> DeleteResult.NOT_FOUND   // someone else deleted it
                !now.state.isTerminal -> DeleteResult.NOT_TERMINAL  // resurrected via retry
                else -> DeleteResult.NOT_FOUND          // shouldn't happen, defensive
            }
        }
        eventBus.publish(
            WebSocketEvent.JobDeleted(
                id = jobId.toString(),
                by = by,
                at = Clock.System.now(),
            ),
        )
        return DeleteResult.DELETED
    }

    override suspend fun reroute(
        jobId: Uuid,
        targetNode: String?,
        targetTag: String?,
        by: String?,
    ): RerouteResult {
        repeat(CANCEL_ATTEMPTS) {
            val current = storage.jobs.findById(jobId) ?: return RerouteResult.NOT_FOUND
            if (current.state.isTerminal) return RerouteResult.ALREADY_TERMINAL

            val ok = withContext(Dispatchers.IO) {
                suspendTransaction(db = database) {
                    val updated = storage.jobs.updateRouting(
                        jobId = jobId,
                        expectedVersion = current.version,
                        targetNode = targetNode,
                        targetTag = targetTag,
                        actor = by,
                    )
                    if (!updated) return@suspendTransaction false
                    // Insert fresh outbox row with the NEW routing key. Same routing-key
                    // computation as enqueue (`node.* > tag.* > queue`).
                    val routingKey = when {
                        targetNode != null -> "node.$targetNode"
                        targetTag != null -> "tag.$targetTag"
                        else -> current.queue
                    }
                    storage.outbox.insert(
                        NewOutboxEntry(
                            jobId = jobId,
                            routingKey = routingKey,
                            priority = current.priority.value,
                            delayMs = 0,
                        ),
                    )
                    true
                }
            }
            if (ok) return RerouteResult.REROUTED
        }
        return RerouteResult.CONFLICT
    }

    override suspend fun enqueueFunctionRef(
        method: KFunction<*>,
        args: List<Any?>,
        options: EnqueueOptions,
    ): Uuid {
        // Pack KFunction + args into the wire payload here (vs in the extension layer)
        // so the configured Json serializer stays a private detail of this impl and
        // Koin's compile-time validator doesn't see a `koin.get<SchedulerCoreConfig>()`
        // propagated up through extension default-parameter expressions.
        val built = FunctionRefEnqueuer.build(method, args, options.targetQualifier, json)
        return insertFunctionRef(built.payload, options)
    }

    override suspend fun enqueueFunctionRefRaw(
        targetType: String,
        methodSignature: String,
        args: List<Any?>,
        options: EnqueueOptions,
    ): Uuid {
        // Compiler-plugin lowering of `enqueueLambda { … }` (DESIGN.md 21.9). The plugin
        // hands us the receiver type + method signature as strings (it can't synthesise a
        // KFunction in IR); buildFromTarget reflects the KFunction back out and produces the
        // identical FunctionRefPayload, so the row is indistinguishable from the explicit
        // `enqueue(Recv::method, …)` path below.
        val built = FunctionRefEnqueuer.buildFromTarget(targetType, methodSignature, args, options.targetQualifier, json)
        return insertFunctionRef(built.payload, options)
    }

    /**
     * Shared tail of both function-ref enqueue entry points: fail-fast Koin check, then the
     * same row + outbox + audit insert as the sealed-class path.
     */
    private suspend fun insertFunctionRef(payload: FunctionRefPayload, options: EnqueueOptions): Uuid {
        // Fail-fast at enqueue if Koin won't be able to resolve this target at execute
        // time. Same exception type as `FunctionRefEnqueuer`'s arg-serialisation check, so
        // callers can `catch (e: IllegalArgumentException)` once for both classes of
        // mis-configuration.
        functionRefBindingResolver.requireResolvable(payload.targetType, payload.targetQualifier)

        // Function-ref jobs piggyback on the sealed-class enqueue path: same row layout,
        // same outbox row, same audit event. The ONLY shape difference is the payload —
        // payload_type is the FUNCTION_REF_PAYLOAD_TYPE sentinel + payload_json holds the
        // FunctionRefPayload's wire form. The worker branches on that at execute time.
        val params = buildFunctionRefParams(payload, options)
        val row = newJobRow(params, state = JobState.ENQUEUED, scheduledAt = null)

        withContext(Dispatchers.IO) {
            suspendTransaction(db = database) {
                storage.jobs.insert(row)
                storage.outbox.insert(
                    NewOutboxEntry(
                        jobId = params.jobId,
                        routingKey = params.routingKey,
                        priority = params.priority,
                        delayMs = 0,
                    ),
                )
                recordCreated(params.jobId, newState = JobState.ENQUEUED)
            }
        }
        emitCreated(params)
        return params.jobId
    }

    private fun buildFunctionRefParams(payload: FunctionRefPayload, options: EnqueueOptions): EnqueueParams {
        val queue = options.queue ?: config.defaultQueue
        val priority = options.priority ?: 0
        val payloadJson = json.encodeToString(FunctionRefPayload.serializer(), payload)
        val routingKey = when {
            options.targetNode != null -> "node.${options.targetNode}"
            options.targetTag != null -> "tag.${options.targetTag}"
            else -> queue
        }
        val contextJson = if (options.captureContext && contextCapture != null) {
            contextCapture.snapshot()?.let { json.encodeToString(ContextSnapshot.serializer(), it) }
        } else null
        return EnqueueParams(
            jobId = Uuid.random(),
            queue = queue,
            priority = priority,
            maxAttempts = options.maxAttempts ?: config.defaultMaxAttempts,
            timeoutSeconds = options.timeout?.inWholeSeconds?.toInt(),
            payloadType = FunctionRefPayload.FUNCTION_REF_PAYLOAD_TYPE,
            payloadJson = payloadJson,
            routingKey = routingKey,
            targetNode = options.targetNode,
            targetTag = options.targetTag,
            contextJson = contextJson,
        )
    }

    override suspend fun retry(jobId: Uuid, by: String?, mode: RetryMode): RetryResult {
        // Bounded retry — if CAS keeps losing, the row is being concurrently mutated.
        // After CANCEL_ATTEMPTS we step back and report whatever final state we see.
        repeat(CANCEL_ATTEMPTS) {
            val current = storage.jobs.findById(jobId) ?: return RetryResult.NOT_FOUND
            if (current.state != JobState.FAILED) return RetryResult.NOT_FAILED

            // manualRetry + outbox.insert in one tx — partial application would leave a
            // ENQUEUED row with no Rabbit dispatch row (job stuck) or vice versa.
            val ok = withContext(Dispatchers.IO) {
                suspendTransaction(db = database) {
                    val updated = storage.jobs.manualRetry(jobId, current.version, by, mode)
                    if (!updated) return@suspendTransaction false
                    val routingKey = when {
                        current.targetNode != null -> "node.${current.targetNode}"
                        current.targetTag != null -> "tag.${current.targetTag}"
                        else -> current.queue
                    }
                    storage.outbox.insert(
                        NewOutboxEntry(
                            jobId = jobId,
                            routingKey = routingKey,
                            priority = current.priority.value,
                            delayMs = 0,
                        ),
                    )
                    true
                }
            }
            if (ok) return RetryResult.RETRIED
            // CAS lost — re-read and try once more.
        }
        return RetryResult.CONFLICT
    }

    private data class EnqueueParams(
        val jobId: Uuid,
        val queue: String,
        val priority: Int,
        val maxAttempts: Int,
        val timeoutSeconds: Int?,
        val payloadType: String,
        val payloadJson: String,
        val routingKey: String,
        val targetNode: String?,
        val targetTag: String?,
        val contextJson: String?,
    )

    private fun buildParams(job: Job, options: EnqueueOptions): EnqueueParams {
        val queue = options.queue ?: config.defaultQueue
        val priority = options.priority ?: 0
        val payloadType = job::class.qualifiedName
            ?: error("Job payload class must have a qualifiedName (no local/anonymous classes)")
        val payloadJson = json.encodeToString(serializer(job::class.starProjectedType), job)

        val routingKey = when {
            options.targetNode != null -> "node.${options.targetNode}"
            options.targetTag != null -> "tag.${options.targetTag}"
            else -> queue
        }

        val contextJson = if (options.captureContext && contextCapture != null) {
            contextCapture.snapshot()?.let { json.encodeToString(ContextSnapshot.serializer(), it) }
        } else null

        return EnqueueParams(
            jobId = Uuid.random(),
            queue = queue,
            priority = priority,
            maxAttempts = options.maxAttempts ?: config.defaultMaxAttempts,
            timeoutSeconds = options.timeout?.inWholeSeconds?.toInt(),
            payloadType = payloadType,
            payloadJson = payloadJson,
            routingKey = routingKey,
            targetNode = options.targetNode,
            targetTag = options.targetTag,
            contextJson = contextJson,
        )
    }

    private fun newJobRow(
        p: EnqueueParams,
        state: JobState,
        scheduledAt: Instant?,
    ): JobRow {
        val now = Clock.System.now()
        return JobRow(
            id = p.jobId,
            state = state,
            queue = p.queue,
            priority = JobPriority(p.priority),
            payloadType = p.payloadType,
            payloadJson = p.payloadJson,
            scheduledAt = scheduledAt,
            attempts = 0,
            maxAttempts = p.maxAttempts,
            timeoutSeconds = p.timeoutSeconds,
            lockedBy = null,
            lockedUntil = null,
            pendingDeps = 0,
            version = 0,
            idempotencyKey = null,
            targetNode = p.targetNode,
            targetTag = p.targetTag,
            progress = null,
            progressMsg = null,
            progressUpdatedAt = null,
            startedAt = null,
            durationMs = null,
            cancelRequestedAt = null,
            cancelRequestedBy = null,
            contextJson = p.contextJson,
            createdAt = now,
            updatedAt = now,
        )
    }
}
