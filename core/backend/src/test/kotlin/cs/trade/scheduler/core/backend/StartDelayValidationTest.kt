package cs.trade.scheduler.core.backend

import cs.trade.scheduler.core.backend.handler.Job
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.nanoseconds

class StartDelayValidationTest {

    private object Payload : Job

    @Test
    fun `invalid delays are rejected by both enqueue and recurring definitions`() {

        for (delay in listOf((-1).milliseconds, Duration.INFINITE, 500.nanoseconds)) {
            assertThrows(IllegalArgumentException::class.java) { EnqueueOptions(maxStartDelay = delay) }
            assertThrows(IllegalArgumentException::class.java) {
                RecurringDefinition(id = "test", cron = "* * * * *", job = Payload, maxStartDelay = delay)
            }
        }
    }

    @Test
    fun `zero and millisecond delays are accepted`() {

        for (delay in listOf(null, Duration.ZERO, 1.milliseconds, 12_345.milliseconds)) {
            assertEquals(delay, EnqueueOptions(maxStartDelay = delay).maxStartDelay)
            assertEquals(delay, RecurringDefinition(
                id = "test", cron = "* * * * *", job = Payload, maxStartDelay = delay,
            ).maxStartDelay)
        }
    }
}
