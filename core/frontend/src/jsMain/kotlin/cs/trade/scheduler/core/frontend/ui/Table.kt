package cs.trade.scheduler.core.frontend.ui

import cs.trade.scheduler.core.frontend.theme.SchedulerColors
import cs.trade.scheduler.core.frontend.theme.SchedulerText
import emotion.react.css
import react.FC
import react.Props
import react.PropsWithChildren
import react.dom.html.ReactHTML.table
import react.dom.html.ReactHTML.tbody
import react.dom.html.ReactHTML.td
import react.dom.html.ReactHTML.th
import react.dom.html.ReactHTML.thead
import react.dom.html.ReactHTML.tr
import web.cssom.BorderCollapse
import web.cssom.Cursor
import web.cssom.Length
import web.cssom.Padding
import web.cssom.Position
import web.cssom.TextAlign
import web.cssom.TextTransform
import web.cssom.integer
import web.cssom.pct
import web.cssom.px

/**
 * Table primitives for the dashboard's operator views (jobs, recurring, workers, types…).
 *
 * A real `<table>` rather than a flex grid: the browser's own column sizing handles wildly
 * uneven content (a 36-char UUID next to a 3-char count) better than any manual track sizing,
 * and it gives correct semantics to assistive tech for free.
 */

public val DataTable: FC<PropsWithChildren> = FC { props ->
    table {
        css {
            width = 100.pct
            borderCollapse = BorderCollapse.collapse
            +SchedulerText.bodySmall
        }
        +props.children
    }
}

/**
 * Sticky header row. Stays put while a long result page scrolls inside its panel — an operator
 * scanning 200 failed jobs shouldn't lose the column names.
 */
public val TableHead: FC<PropsWithChildren> = FC { props ->
    thead {
        css {
            position = Position.sticky
            top = 0.px
            zIndex = integer(1)
            backgroundColor = SchedulerColors.surface
        }
        +props.children
    }
}

public val TableBody: FC<PropsWithChildren> = FC { props ->
    tbody { +props.children }
}

external interface TableRowProps : PropsWithChildren {
    var onClick: (() -> Unit)?
    var selected: Boolean?

    /**
     * Play the arrival animation (slide down from above + a brief cobalt wash). Set only for rows
     * that genuinely just appeared in a live list — see `useArrivingRows`.
     */
    var arriving: Boolean?
}

public val TableRow: FC<TableRowProps> = FC { props ->
    val clickable = props.onClick != null
    tr {
        props.onClick?.let { handler -> onClick = { handler() } }
        css {
            borderBottom = web.cssom.Border(1.px, web.cssom.LineStyle.solid, SchedulerColors.outlineVariant)
            if (props.selected == true) backgroundColor = SchedulerColors.primaryContainer
            if (props.arriving == true) asDynamic().animation = "sch-row-in 0.5s ease-out"
            if (clickable) {
                cursor = Cursor.pointer
                hover {
                    backgroundColor = if (props.selected == true) {
                        SchedulerColors.primaryContainer
                    } else {
                        SchedulerColors.surfaceContainerLow
                    }
                }
            }
        }
        +props.children
    }
}

external interface TableCellProps : PropsWithChildren {
    var align: TextAlign?
    var width: Length?
    var nowrap: Boolean?
}

public val TableCell: FC<TableCellProps> = FC { props ->
    td {
        css {
            padding = Padding(9.px, 12.px)
            props.align?.let { textAlign = it }
            props.width?.let { width = it }
            if (props.nowrap == true) whiteSpace = web.cssom.WhiteSpace.nowrap
            color = SchedulerColors.onSurface
            verticalAlign = web.cssom.VerticalAlign.middle
        }
        +props.children
    }
}

external interface TableHeaderCellProps : PropsWithChildren {
    var align: TextAlign?
    var width: Length?

    /** Present when the column is sortable — clicking cycles the sort through the caller. */
    var onClick: (() -> Unit)?
}

public val TableHeaderCell: FC<TableHeaderCellProps> = FC { props ->
    th {
        props.onClick?.let { handler -> onClick = { handler() } }
        css {
            padding = Padding(10.px, 12.px)
            textAlign = props.align ?: TextAlign.left
            props.width?.let { width = it }
            borderBottom = web.cssom.Border(1.px, web.cssom.LineStyle.solid, SchedulerColors.outline)
            color = SchedulerColors.onSurfaceVariant
            +SchedulerText.labelSmall
            textTransform = TextTransform.uppercase
            whiteSpace = web.cssom.WhiteSpace.nowrap
            userSelect = web.cssom.None.none
            if (props.onClick != null) {
                cursor = Cursor.pointer
                hover { color = SchedulerColors.onSurface }
            }
        }
        +props.children
    }
}

/** Full-width row used to host an empty state or a loading spinner inside a table body. */
external interface TableMessageRowProps : PropsWithChildren {
    var columns: Int
}

public val TableMessageRow: FC<TableMessageRowProps> = FC { props ->
    tr {
        td {
            colSpan = props.columns
            css { padding = 0.px }
            +props.children
        }
    }
}
