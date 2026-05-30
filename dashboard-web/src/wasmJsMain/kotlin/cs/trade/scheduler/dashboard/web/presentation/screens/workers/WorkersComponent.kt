package cs.trade.scheduler.dashboard.web.presentation.screens.workers

import com.arkivanov.decompose.value.Value
import cs.trade.scheduler.shared.dto.WorkerDto

public interface WorkersComponent {
    public val model: Value<Model>

    public fun onRefreshClicked()
    public fun onBackClicked()

    /** Auto-refresh cadence — `null` = off (manual Refresh only), else re-list every N seconds. */
    public fun onAutoRefreshChanged(seconds: Int?)

    /** Last HB / Uptime: `false` = relative ("3m ago"), `true` = absolute clock ("14:30:05"). */
    public fun onTimeModeChanged(absolute: Boolean)

    public data class Model(
        val items: List<WorkerDto> = emptyList(),
        val loading: Boolean = false,
        val error: String? = null,
        val autoRefreshSeconds: Int? = null,
        val timeAbsolute: Boolean = false,
    )
}
