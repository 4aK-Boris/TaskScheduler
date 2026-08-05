package cs.trade.scheduler.dashboard.web.presentation.screens.typesstats

import com.arkivanov.decompose.value.Value
import cs.trade.scheduler.shared.dto.TypeStatsDto
import cs.trade.scheduler.shared.dto.TypeStatsRange

public interface TypeStatsComponent {
    public val model: Value<Model>

    public fun onRangeChanged(range: TypeStatsRange)
    public fun onRefresh()
    public fun onBackClicked()

    public data class Model(
        val range: TypeStatsRange = TypeStatsRange.LAST_24_HOURS,
        val items: List<TypeStatsDto> = emptyList(),
        val loading: Boolean = false,
        val error: String? = null,
    )
}

/** Maps the [TypeStatsRange] enum to its hour-count over the wire. */
public fun TypeStatsRange.toHours(): Int = when (this) {
    TypeStatsRange.LAST_1_HOUR -> 1
    TypeStatsRange.LAST_3_HOURS -> 3
    TypeStatsRange.LAST_6_HOURS -> 6
    TypeStatsRange.LAST_12_HOURS -> 12
    TypeStatsRange.LAST_24_HOURS -> 24
    TypeStatsRange.LAST_3_DAYS -> 24 * 3
    TypeStatsRange.LAST_7_DAYS -> 24 * 7
    TypeStatsRange.LAST_30_DAYS -> 24 * 30
}

public fun TypeStatsRange.label(): String = when (this) {
    TypeStatsRange.LAST_1_HOUR -> "Last 1h"
    TypeStatsRange.LAST_3_HOURS -> "Last 3h"
    TypeStatsRange.LAST_6_HOURS -> "Last 6h"
    TypeStatsRange.LAST_12_HOURS -> "Last 12h"
    TypeStatsRange.LAST_24_HOURS -> "Last 24h"
    TypeStatsRange.LAST_3_DAYS -> "Last 3d"
    TypeStatsRange.LAST_7_DAYS -> "Last 7d"
    TypeStatsRange.LAST_30_DAYS -> "Last 30d"
}
