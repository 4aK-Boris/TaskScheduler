package cs.trade.scheduler.engine.worker

import cs.trade.scheduler.engine.worker.infrastructure.ProgressBarImpl
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Pure arithmetic / force-flag check for [ProgressBarImpl] — no DB, no throttle. A fake
 * reporter records every `(progress, succeeded, failed, total, force)` tuple the bar emits
 * so we can assert the derived fraction and that the completing increment sets `force=true`.
 */
class ProgressBarImplTest {

    private data class Sample(
        val progress: Float,
        val msg: String?,
        val succeeded: Long?,
        val failed: Long?,
        val total: Long?,
        val force: Boolean,
    )

    private fun barOver(total: Long, sink: MutableList<Sample>) =
        ProgressBarImpl(total) { progress, msg, s, f, t, force ->
            sink += Sample(progress, msg, s, f, t, force)
        }

    @Test
    fun `derives fraction from succeeded plus failed and forces the completing sample`() = runBlocking {
        val samples = mutableListOf<Sample>()
        val bar = barOver(total = 4, sink = samples)

        bar.succeeded()                 // 1/4
        bar.failed(msg = "boom")        // 2/4
        bar.succeeded(count = 2)        // 4/4 → completes

        assertEquals(3, samples.size)
        assertEquals(Sample(0.25f, null, 1, 0, 4, false), samples[0])
        assertEquals(Sample(0.5f, "boom", 1, 1, 4, false), samples[1])
        assertEquals(Sample(1.0f, null, 3, 1, 4, true), samples[2])

        assertEquals(3L, bar.succeeded)
        assertEquals(1L, bar.failed)
        assertEquals(4L, bar.processed)
        assertEquals(1.0f, bar.fraction)
    }

    @Test
    fun `total of zero reports fraction 1 and forces immediately`() = runBlocking {
        val samples = mutableListOf<Sample>()
        val bar = barOver(total = 0, sink = samples)

        bar.succeeded()

        assertEquals(1, samples.size)
        assertEquals(Sample(1.0f, null, 1, 0, 0, true), samples.single())
        assertEquals(1.0f, bar.fraction)
    }
}
