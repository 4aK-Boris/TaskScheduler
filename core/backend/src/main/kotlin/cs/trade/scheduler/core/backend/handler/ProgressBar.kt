package cs.trade.scheduler.core.backend.handler

/**
 * JobRunr-style counting progress bar over a known number of work items, obtained via
 * [JobContext.progressBar]. The handler marks each item [succeeded] or [failed]; the bar
 * derives the 0..1 fraction `(succeeded + failed) / total` and persists it (throttled to
 * one DB write per second, with the completing sample always flushed). See DESIGN.md 22.3.
 *
 * ```
 * val bar = ctx.progressBar(total = items.size.toLong())
 * for (item in items) {
 *     try { process(item); bar.succeeded() }
 *     catch (e: Exception) { bar.failed() }
 * }
 * ```
 *
 * Both outcomes advance the bar — an item is "processed" whether it worked or not. The
 * `succeeded` / `failed` split is surfaced separately on the dashboard (green vs red).
 *
 * Counters are safe to increment from multiple coroutines concurrently.
 */
public interface ProgressBar {
    /** Denominator fixed at creation. */
    public val total: Long

    /** Items marked successful so far. */
    public val succeeded: Long

    /** Items marked failed so far. */
    public val failed: Long

    /** `succeeded + failed`. */
    public val processed: Long

    /** `processed / total`, clamped to `0f..1f`. `1f` when [total] <= 0 (nothing to do). */
    public val fraction: Float

    /**
     * Mark [count] items (default 1) as successfully processed and report the new progress.
     * Optional [msg] becomes the bar's current step label. Persistence is throttled; the
     * sample that completes the bar (`processed >= total`) bypasses the throttle so the bar
     * never sticks just short of 100%.
     */
    public suspend fun succeeded(count: Long = 1L, msg: String? = null)

    /** Mark [count] items (default 1) as failed. See [succeeded] for throttling semantics. */
    public suspend fun failed(count: Long = 1L, msg: String? = null)
}
