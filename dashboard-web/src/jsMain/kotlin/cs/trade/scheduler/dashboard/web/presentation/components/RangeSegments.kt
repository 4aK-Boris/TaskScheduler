package cs.trade.scheduler.dashboard.web.presentation.components

import cs.trade.scheduler.core.frontend.theme.SchedulerColors
import cs.trade.scheduler.core.frontend.theme.SchedulerRadius
import cs.trade.scheduler.core.frontend.theme.SchedulerText
import cs.trade.scheduler.core.frontend.ui.flexRow
import cs.trade.scheduler.core.frontend.ui.hairline
import cs.trade.scheduler.shared.dto.TypeStatsRange
import emotion.react.css
import react.FC
import react.Key
import react.Props
import react.dom.html.ReactHTML.button
import react.dom.html.ReactHTML.div
import web.cssom.Cursor
import web.cssom.None
import web.cssom.Overflow
import web.cssom.Padding
import web.cssom.px
import web.html.ButtonType
import web.html.button as buttonType

/**
 * Compact segmented time-window control (1h … 30d) — the selected segment fills cobalt. Shared by
 * the Type Stats and Stats screens so the picker reads and behaves identically on both.
 */
external interface RangeSegmentsProps : Props {
    var current: TypeStatsRange
    var onSelected: (TypeStatsRange) -> Unit
}

public val RangeSegments: FC<RangeSegmentsProps> = FC { props ->
    div {
        css {
            flexRow()
            borderRadius = SchedulerRadius.small
            hairline()
            // Clip the children's square corners to the container's rounded ones.
            overflow = Overflow.hidden
        }
        TypeStatsRange.entries.forEach { range ->
            val selected = range == props.current
            button {
                key = Key(range.name)
                type = ButtonType.buttonType
                onClick = { props.onSelected(range) }
                css {
                    padding = Padding(7.px, 14.px)
                    border = None.none
                    cursor = Cursor.pointer
                    +SchedulerText.labelMedium
                    backgroundColor = if (selected) SchedulerColors.primary else SchedulerColors.transparent
                    color = if (selected) SchedulerColors.onPrimary else SchedulerColors.onSurfaceVariant
                    asDynamic().transition = "background-color 0.16s ease-out, color 0.16s ease-out"
                    if (!selected) {
                        hover {
                            backgroundColor = SchedulerColors.surfaceContainerHigh
                            color = SchedulerColors.onSurface
                        }
                    }
                }
                +range.shortLabel()
            }
        }
    }
}

private fun TypeStatsRange.shortLabel(): String = when (this) {
    TypeStatsRange.LAST_1_HOUR -> "1h"
    TypeStatsRange.LAST_3_HOURS -> "3h"
    TypeStatsRange.LAST_6_HOURS -> "6h"
    TypeStatsRange.LAST_12_HOURS -> "12h"
    TypeStatsRange.LAST_24_HOURS -> "24h"
    TypeStatsRange.LAST_3_DAYS -> "3d"
    TypeStatsRange.LAST_7_DAYS -> "7d"
    TypeStatsRange.LAST_30_DAYS -> "30d"
}
