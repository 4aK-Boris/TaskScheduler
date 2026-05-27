package cs.trade.scheduler.dashboard.server.api.extractors

import cs.trade.scheduler.core.backend.EMPTY_STRING
import io.ktor.server.application.ApplicationCall
import org.koin.core.annotation.Single

@Single
public class RecurringExtractor {
    public fun extractId(call: ApplicationCall): String =
        call.parameters[ID_PARAMETER_NAME] ?: EMPTY_STRING

    public companion object {
        public const val ID_PARAMETER_NAME: String = "recurringId"
    }
}
