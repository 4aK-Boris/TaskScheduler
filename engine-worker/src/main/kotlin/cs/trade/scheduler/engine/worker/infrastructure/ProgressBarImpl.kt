package cs.trade.scheduler.engine.worker.infrastructure

import cs.trade.scheduler.core.backend.handler.ProgressBar
import java.util.concurrent.atomic.AtomicLong

/**
 * Counting [ProgressBar] backed by two [AtomicLong] counters so a handler can increment
 * from several coroutines/threads safely. Each increment derives the fraction and routes
 * it through [reporter] — the same throttled funnel `JobContextImpl.report` uses for
 * `updateProgress`. The completing increment (`processed >= total`) sets `force = true` so
 * the final sample bypasses the throttle and the bar reaches 100%.
 *
 * The bar holds no DB/throttle state of its own; all of that lives on the owning
 * [JobContextImpl] and dies with the handler frame.
 */
internal class ProgressBarImpl(
    override val total: Long,
    private val reporter: suspend (
        progress: Float,
        msg: String?,
        succeeded: Long?,
        failed: Long?,
        total: Long?,
        force: Boolean,
    ) -> Unit,
) : ProgressBar {

    private val succeededCount = AtomicLong(0)
    private val failedCount = AtomicLong(0)

    override val succeeded: Long get() = succeededCount.get()
    override val failed: Long get() = failedCount.get()
    override val processed: Long get() = succeededCount.get() + failedCount.get()
    override val fraction: Float get() = fractionFor(processed)

    override suspend fun succeeded(count: Long, msg: String?) {
        succeededCount.addAndGet(count)
        flush(msg)
    }

    override suspend fun failed(count: Long, msg: String?) {
        failedCount.addAndGet(count)
        flush(msg)
    }

    private suspend fun flush(msg: String?) {
        // Snapshot once so the reported fraction and the reported counts agree even if
        // another coroutine increments between reads.
        val s = succeededCount.get()
        val f = failedCount.get()
        val done = s + f
        reporter(fractionFor(done), msg, s, f, total, done >= total)
    }

    private fun fractionFor(done: Long): Float =
        if (total <= 0L) 1f else (done.toFloat() / total.toFloat()).coerceIn(0f, 1f)
}
