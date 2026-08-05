package cs.trade.scheduler.dashboard.web.presentation.screens.upcoming

import com.arkivanov.decompose.value.Value
import cs.trade.scheduler.shared.dto.UpcomingOccurrenceDto

/**
 * Decompose component for the "Upcoming" screen — an agenda of future-scheduled jobs due within a
 * chosen window, soonest-first. Reuses the `/api/jobs?scheduledWithinMinutes=` backend filter; this
 * screen just pins the window and drops the rest of the Jobs-list machinery (state chips, DLQ, bulk).
 */
public interface UpcomingComponent {
    public val model: Value<Model>

    public fun onRefreshClicked()
    public fun onBackClicked()

    /** Look-ahead window in minutes (60 / 360 / 1440 / 4320). */
    public fun onWindowChanged(minutes: Int)

    /** Row tap on a real future job → open its detail. */
    public fun onJobClicked(jobId: String)

    /** Row tap on a recurring occurrence (no job row exists yet) → open the Recurring section. */
    public fun onRecurringClicked()

    /** Auto-refresh cadence — `null` = off, else re-list every N seconds. */
    public fun onAutoRefreshChanged(seconds: Int?)

    /** Scheduled column: `false` = relative ("in 18m"), `true` = absolute ("01.06.2026 14:30:05"). */
    public fun onTimeModeChanged(absolute: Boolean)

    public data class Model(
        val items: List<UpcomingOccurrenceDto> = emptyList(),
        // True when the server capped the result (more runs in the window than the cap).
        val truncated: Boolean = false,
        val windowMinutes: Int = DEFAULT_WINDOW_MINUTES,
        val loading: Boolean = false,
        val error: String? = null,
        val autoRefreshSeconds: Int? = null,
        val timeAbsolute: Boolean = false,
    )

    public companion object {
        public const val DEFAULT_WINDOW_MINUTES: Int = 60
    }
}
