package cs.trade.scheduler.core.backend

import cs.trade.scheduler.core.backend.handler.Job
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class RepeatAfterCompletionValidationTest {

    private object Payload : Job

    @Test
    fun `continuous mode needs no cron`() {

        val definition = RecurringDefinition(id = "continuous", job = Payload, repeatAfterCompletion = true)

        assertTrue(definition.repeatAfterCompletion)
        assertTrue(definition.cron.isEmpty())
    }

    @Test
    fun `continuous mode rejects an ambiguous cron or timezone`() {

        assertThrows(IllegalArgumentException::class.java) {
            RecurringDefinition(id = "test", job = Payload, repeatAfterCompletion = true, cron = "* * * * *")
        }
        assertThrows(IllegalArgumentException::class.java) {
            RecurringDefinition(id = "test", job = Payload, repeatAfterCompletion = true, timezone = "UTC")
        }
    }
}
