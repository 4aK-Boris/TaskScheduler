package cs.trade.scheduler.core.backend.idempotency

import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * Handler-side dedup primitive (DESIGN.md 17.3). Use inside [cs.trade.scheduler.core.backend.handler.JobHandler.execute]
 * to make individual side-effects exactly-once even when the same job is delivered twice:
 *
 * ```
 * class SendEmailHandler(private val mailer: Mailer, private val idem: IdempotencyStore)
 *     : JobHandler<SendEmail> {
 *     override suspend fun execute(ctx: JobContext, job: SendEmail) {
 *         if (!idem.tryMark(ctx.jobId)) return    // duplicate delivery — skip
 *         mailer.send(...)
 *     }
 * }
 * ```
 *
 * Multi-step jobs use the `action` parameter to dedup each step independently:
 *
 * ```
 * override suspend fun execute(ctx: JobContext, job: ProcessOrder) {
 *     if (idem.tryMark(ctx.jobId, "charge"))   chargePayment(job)
 *     if (idem.tryMark(ctx.jobId, "notify"))   sendConfirmation(job)
 *     if (idem.tryMark(ctx.jobId, "fulfill"))  scheduleShipping(job)
 * }
 * ```
 *
 * **Not the same as `enqueueOnce` (DESIGN.md 17.4):** `enqueueOnce` is producer-side
 * (prevents double-CREATION); this is handler-side (prevents double-EXECUTION of one job's
 * side-effects across retries / redeliveries). Both serve real failure modes.
 *
 * The default impl in `storage-postgres` is `PostgresIdempotencyStore`, backed by the
 * `idempotency_log` table with an independent TTL (DESIGN.md 18.4) — typically longer
 * than job retention so external API idempotency keys outlive the originating job row.
 */
@OptIn(ExperimentalUuidApi::class)
public interface IdempotencyStore {

    /**
     * Atomically marks `(jobId, action)` as processed. Returns `true` if this is the first
     * call for the key — the caller should perform its side-effect. Returns `false` if
     * the key was already marked — the caller should skip.
     *
     * Race-free across concurrent workers (PRIMARY KEY enforces it at the DB level).
     */
    public suspend fun tryMark(jobId: Uuid, action: String = "default"): Boolean

    /**
     * No-op fallback. Bound by default when the user disables dedup or runs without a
     * persistent store. Always returns `true` — handlers proceed without dedup.
     */
    public object Noop : IdempotencyStore {
        override suspend fun tryMark(jobId: Uuid, action: String): Boolean = true
    }
}
