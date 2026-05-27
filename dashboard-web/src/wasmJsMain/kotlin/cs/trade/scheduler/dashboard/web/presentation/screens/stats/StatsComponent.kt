package cs.trade.scheduler.dashboard.web.presentation.screens.stats

import com.arkivanov.decompose.value.Value
import cs.trade.scheduler.shared.dto.StatsOverviewResponse

public interface StatsComponent {
    public val model: Value<Model>

    public fun onRefreshClicked()
    public fun onBackClicked()

    public data class Model(
        val overview: StatsOverviewResponse? = null,
        val loading: Boolean = false,
        val error: String? = null,
    )
}
