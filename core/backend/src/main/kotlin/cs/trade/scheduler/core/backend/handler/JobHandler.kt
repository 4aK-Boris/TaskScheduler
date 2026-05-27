package cs.trade.scheduler.core.backend.handler

import cs.trade.scheduler.core.backend.handler.retry.RetryPolicy

/**
 * Contract for user code that executes a job. See DESIGN.md section 12.4.
 *
 * Implementations are discovered via Koin `@Single(binds = [JobHandler::class])` + this module's
 * `@JobType(...)` annotation. The scheduler builds a `KClass<out Job> -> JobHandler<*>` map at
 * `scheduler.start()` time and dispatches incoming jobs to the matching handler.
 *
 * ```
 * @Single(binds = [JobHandler::class])
 * @JobType(SendEmail::class)
 * class SendEmailHandler(private val mailer: Mailer) : JobHandler<SendEmail> {
 *     override suspend fun execute(ctx: JobContext, job: SendEmail) {
 *         mailer.send(job.userId, job.template)
 *     }
 * }
 * ```
 */
public interface JobHandler<T : Job> {
    public suspend fun execute(ctx: JobContext, job: T)

    /** Per-handler override of the global retry policy. `null` = use global default. */
    public val retryPolicy: RetryPolicy? get() = null

    /** Default priority for jobs of this type. 0..10. See DESIGN.md 19.3. */
    public val defaultPriority: Int get() = 0

    /**
     * Called once AFTER the job is finally marked FAILED (max_attempts exhausted or
     * non-retriable exception). `ctx.attempt` is the final attempt count.
     * Default impl is empty. See DESIGN.md 16.6.
     */
    public suspend fun onFinalFailure(ctx: JobContext, job: T, error: Throwable) {}
}
