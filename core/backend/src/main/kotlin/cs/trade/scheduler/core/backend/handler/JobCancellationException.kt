package cs.trade.scheduler.core.backend.handler

/**
 * Sentinel a [JobHandler] throws to opt into cooperative cancellation. Handlers that
 * poll [JobContext.isCancellationRequested] and decide to honor a cancel request should
 * throw this rather than just returning — the WorkerPool then transitions the job to
 * CANCELLED (terminal, no retry), as opposed to SUCCEEDED on a normal return or FAILED
 * on any other throwable.
 *
 * Example:
 * ```
 * override suspend fun execute(ctx: JobContext, job: BulkExport) {
 *     for (chunk in chunks) {
 *         if (ctx.isCancellationRequested()) throw JobCancellationException()
 *         process(chunk)
 *     }
 * }
 * ```
 */
public class JobCancellationException(
    message: String = "Job execution cancelled by request",
    cause: Throwable? = null,
) : RuntimeException(message, cause)
