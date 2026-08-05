package cs.trade.scheduler.dashboard.web.presentation.screens.stats

import com.arkivanov.decompose.value.Value
import cs.trade.scheduler.shared.dto.StatsOverviewResponse
import cs.trade.scheduler.shared.dto.TypeStatsRange

public interface StatsComponent {
    public val model: Value<Model>

    public fun onRefreshClicked()
    public fun onBackClicked()

    /** Time window for the terminal outcome counts (succeeded/failed/cancelled); live states are "now". */
    public fun onRangeChanged(range: TypeStatsRange)

    public data class Model(
        val overview: StatsOverviewResponse? = null,
        val loading: Boolean = false,
        val error: String? = null,
        val range: TypeStatsRange = TypeStatsRange.LAST_24_HOURS,
    )
}
