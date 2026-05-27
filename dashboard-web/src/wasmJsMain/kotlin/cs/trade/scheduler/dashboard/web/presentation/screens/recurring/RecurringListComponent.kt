package cs.trade.scheduler.dashboard.web.presentation.screens.recurring

import com.arkivanov.decompose.value.Value
import cs.trade.scheduler.shared.dto.RecurringJobDto

public interface RecurringListComponent {
    public val model: Value<Model>

    public fun onRefreshClicked()
    public fun onToggleClicked(id: String, enable: Boolean)
    public fun onBackClicked()

    public data class Model(
        val items: List<RecurringJobDto> = emptyList(),
        val loading: Boolean = false,
        val error: String? = null,
        val togglingId: String? = null,
    )
}
