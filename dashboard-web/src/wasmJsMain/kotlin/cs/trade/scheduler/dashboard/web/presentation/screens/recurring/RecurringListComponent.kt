package cs.trade.scheduler.dashboard.web.presentation.screens.recurring

import com.arkivanov.decompose.value.Value
import cs.trade.scheduler.shared.dto.RecurringJobDto

public interface RecurringListComponent {
    public val model: Value<Model>

    public fun onRefreshClicked()
    public fun onToggleClicked(id: String, enable: Boolean)
    public fun onBackClicked()

    /** Next/Last columns: `false` = relative ("2h ago"), `true` = absolute clock ("14:30:05"). */
    public fun onAgeModeChanged(absolute: Boolean)

    /** Auto-refresh cadence — `null` = off (manual Refresh only), else re-list every N seconds. */
    public fun onAutoRefreshChanged(seconds: Int?)

    public data class Model(
        val items: List<RecurringJobDto> = emptyList(),
        val loading: Boolean = false,
        val error: String? = null,
        val togglingId: String? = null,
        val ageAbsolute: Boolean = false,
        val autoRefreshSeconds: Int? = null,
    )
}
