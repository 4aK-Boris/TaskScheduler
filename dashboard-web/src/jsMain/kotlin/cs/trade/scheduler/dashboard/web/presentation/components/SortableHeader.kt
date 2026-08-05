package cs.trade.scheduler.dashboard.web.presentation.components

import cs.trade.scheduler.core.frontend.theme.SchedulerColors
import cs.trade.scheduler.core.frontend.ui.ChevronDownIcon
import cs.trade.scheduler.core.frontend.ui.ChevronUpIcon
import cs.trade.scheduler.core.frontend.ui.TableHeaderCell
import cs.trade.scheduler.core.frontend.ui.flexRow
import emotion.react.css
import react.FC
import react.Props
import react.dom.html.ReactHTML.span
import web.cssom.AlignItems
import web.cssom.JustifyContent
import web.cssom.Length
import web.cssom.TextAlign
import web.cssom.px

/** Sort direction for a [SortableHeaderCell]. */
public enum class SortDirection { ASC, DESC }

/**
 * Clickable table-header cell with a sort indicator — the shared building block for sortable
 * tables. Matches the plain header styling (uppercase, tracked, onSurfaceVariant) but tints
 * cobalt and shows a chevron when it's the active sort column. A click bubbles to `onClick`;
 * the table owns the toggle logic (same column → flip direction, other column → switch).
 *
 * [numeric] right-aligns the content, and the arrow then leads instead of trails, so it lines up
 * with the numeric cells below it.
 */
external interface SortableHeaderCellProps : Props {
    var label: String
    var active: Boolean
    var direction: SortDirection
    var onClick: () -> Unit
    var numeric: Boolean?
    var width: Length?
}

public val SortableHeaderCell: FC<SortableHeaderCellProps> = FC { props ->
    val numeric = props.numeric == true
    TableHeaderCell {
        onClick = props.onClick
        props.width?.let { width = it }
        align = if (numeric) TextAlign.right else TextAlign.left

        span {
            css {
                flexRow(gap = 4.px, align = AlignItems.center)
                display = web.cssom.Display.inlineFlex
                justifyContent = if (numeric) JustifyContent.flexEnd else JustifyContent.flexStart
                if (props.active) color = SchedulerColors.primary
            }
            if (numeric) {
                sortChevron(props)
                +props.label.uppercase()
            } else {
                +props.label.uppercase()
                sortChevron(props)
            }
        }
    }
}

private fun react.ChildrenBuilder.sortChevron(props: SortableHeaderCellProps) {
    if (props.active) {
        if (props.direction == SortDirection.ASC) {
            ChevronUpIcon { size = CHEVRON_SIZE }
        } else {
            ChevronDownIcon { size = CHEVRON_SIZE }
        }
    }
}

private val CHEVRON_SIZE = 12.px
