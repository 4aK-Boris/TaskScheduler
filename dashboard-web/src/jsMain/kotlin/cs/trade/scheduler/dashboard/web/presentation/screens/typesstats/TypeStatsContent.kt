package cs.trade.scheduler.dashboard.web.presentation.screens.typesstats

import cs.trade.scheduler.core.frontend.react.useValue
import cs.trade.scheduler.core.frontend.theme.SchedulerColors
import cs.trade.scheduler.core.frontend.theme.SchedulerRadius
import cs.trade.scheduler.core.frontend.theme.SchedulerText
import cs.trade.scheduler.core.frontend.ui.Button
import cs.trade.scheduler.core.frontend.ui.DataTable
import cs.trade.scheduler.core.frontend.ui.EmptyState
import cs.trade.scheduler.core.frontend.ui.ErrorBanner
import cs.trade.scheduler.core.frontend.ui.TableBody
import cs.trade.scheduler.core.frontend.ui.TableCell
import cs.trade.scheduler.core.frontend.ui.TableHead
import cs.trade.scheduler.core.frontend.ui.TableHeaderCell
import cs.trade.scheduler.core.frontend.ui.TableMessageRow
import cs.trade.scheduler.core.frontend.ui.TableRow
import cs.trade.scheduler.core.frontend.ui.flexRow
import cs.trade.scheduler.dashboard.web.presentation.components.CopyableText
import cs.trade.scheduler.dashboard.web.presentation.components.ListScreen
import cs.trade.scheduler.dashboard.web.presentation.components.RangeSegments
import cs.trade.scheduler.dashboard.web.presentation.components.SkeletonRows
import cs.trade.scheduler.dashboard.web.presentation.components.SortableHeaderCell
import cs.trade.scheduler.dashboard.web.presentation.components.useTableSort
import cs.trade.scheduler.shared.dto.TypeStatsDto
import emotion.react.css
import react.FC
import react.Key
import react.Props
import react.create
import react.dom.html.ReactHTML.div
import react.dom.html.ReactHTML.span
import react.dom.html.ReactHTML.tr
import react.useMemo
import web.cssom.Color
import web.cssom.Length
import web.cssom.Overflow
import web.cssom.TextAlign
import web.cssom.number
import web.cssom.pct
import web.cssom.px

/**
 * Per-(type, queue) throughput and latency over a chosen window.
 *
 * The screen isn't paginated — the whole window's aggregate arrives in one response — so sorting
 * is client-side across the full list.
 */
external interface TypeStatsContentProps : Props {
    var component: TypeStatsComponent
}

public val TypeStatsContent: FC<TypeStatsContentProps> = FC { props ->
    val component = props.component
    val state = useValue(component.model)
    val sort = useTableSort(TsSort.SUCCESS, ::naturalAscending)
    val sortedItems = useMemo(state.items, sort.key, sort.ascending) {
        sort.sort(state.items, ::comparatorFor)
    }

    ListScreen {
        title = "Type Stats"
        count = state.items.size.toLong()
        actions = TypeStatsActions.create { this.component = component }

        DataTable {
            TableHead {
                tr {
                    COLUMNS.forEach { column ->
                        if (column.sortKey != null) {
                            SortableHeaderCell {
                                key = Key(column.label)
                                label = column.label
                                width = column.width
                                numeric = column.numeric
                                active = sort.isActive(column.sortKey)
                                direction = sort.directionOf(column.sortKey)
                                onClick = sort.onSort(column.sortKey)
                            }
                        } else {
                            TableHeaderCell {
                                key = Key(column.label)
                                width = column.width
                                +column.label
                            }
                        }
                    }
                }
            }
            TableBody {
                when {
                    state.loading && state.items.isEmpty() -> TableMessageRow {
                        columns = COLUMNS.size
                        SkeletonRows {
                            rows = 7
                            widths = listOf(240.px, 90.px, 120.px, 60.px, 60.px, 60.px)
                        }
                    }

                    state.error != null -> TableMessageRow {
                        columns = COLUMNS.size
                        div {
                            css { padding = 16.px }
                            ErrorBanner {
                                message = "Error: ${state.error}"
                                onRetry = component::onRefresh
                            }
                        }
                    }

                    state.items.isEmpty() -> TableMessageRow {
                        columns = COLUMNS.size
                        EmptyState {
                            title = "No data in selected window"
                            hint = "Widen the range, or wait for jobs of these types to finish."
                        }
                    }

                    // (payloadType, queue) is the natural grouping key — two queues for one type
                    // render as distinct rows.
                    else -> sortedItems.forEach { row ->
                        TypeStatsRow {
                            key = Key("${row.payloadType}|${row.queue}")
                            item = row
                        }
                    }
                }
            }
        }
    }
}

private external interface TypeStatsActionsProps : Props {
    var component: TypeStatsComponent
}

private val TypeStatsActions: FC<TypeStatsActionsProps> = FC { props ->
    val component = props.component
    val state = useValue(component.model)

    RangeSegments {
        current = state.range
        onSelected = component::onRangeChanged
    }
    Button {
        onClick = component::onBackClicked
        +"Back"
    }
    Button {
        onClick = component::onRefresh
        +"Refresh"
    }
}

private external interface TypeStatsRowProps : Props {
    var item: TypeStatsDto
}

private val TypeStatsRow: FC<TypeStatsRowProps> = FC { props ->
    val row = props.item
    TableRow {
        TableCell {
            CopyableText {
                text = row.payloadType
                style = SchedulerText.mono
            }
        }
        TableCell { +row.queue }
        TableCell {
            OutcomeBar {
                success = row.successCount
                failed = row.failedCount
                cancelled = row.cancelledCount
            }
        }
        numCell(row.successCount.toString(), SchedulerColors.success)
        numCell(
            text = row.failedCount.toString(),
            color = if (row.failedCount > 0) SchedulerColors.error else SchedulerColors.onSurface,
        )
        numCell(row.cancelledCount.toString())
        numCell(row.retryCount.toString())
        numCell(row.avgDurationMs.fmt(), mono = true)
        numCell(row.minDurationMs.fmt(), mono = true)
        numCell(row.maxDurationMs.fmt(), mono = true)
        numCell(row.p95DurationMs.fmt(), mono = true)
    }
}

private fun react.ChildrenBuilder.numCell(
    text: String,
    color: Color = SchedulerColors.onSurface,
    mono: Boolean = false,
) {
    TableCell {
        align = TextAlign.right
        nowrap = true
        span {
            css {
                if (mono) +SchedulerText.mono
                this.color = color
            }
            +text
        }
    }
}

private external interface OutcomeBarProps : Props {
    var success: Long
    var failed: Long
    var cancelled: Long
}

/** Thin stacked proportion bar: success (green) / failed (red) / cancelled (grey) of the total. */
private val OutcomeBar: FC<OutcomeBarProps> = FC { props ->
    val total = props.success + props.failed + props.cancelled
    div {
        title = "${props.success} succeeded, ${props.failed} failed, ${props.cancelled} cancelled"
        css {
            flexRow()
            width = 100.pct
            height = 8.px
            borderRadius = SchedulerRadius.small
            overflow = Overflow.hidden
            backgroundColor = SchedulerColors.surfaceContainerHigh
        }
        if (total > 0L) {
            segment(props.success, SchedulerColors.success)
            segment(props.failed, SchedulerColors.error)
            segment(props.cancelled, SchedulerColors.onSurfaceVariant)
        }
    }
}

private fun react.ChildrenBuilder.segment(count: Long, fill: Color) {
    if (count > 0) {
        div {
            css {
                flexGrow = number(count.toDouble())
                height = 100.pct
                backgroundColor = fill
            }
        }
    }
}

private class StatsColumn(
    val label: String,
    val width: Length?,
    val sortKey: TsSort?,
    val numeric: Boolean = false,
)

private val COLUMNS: List<StatsColumn> = listOf(
    // Type takes the flexible width; everything else is fixed so the numbers line up.
    StatsColumn("Type", null, TsSort.TYPE),
    StatsColumn("Queue", 140.px, TsSort.QUEUE),
    // Outcome is a proportion bar, not a value — left unsortable.
    StatsColumn("Outcome", 170.px, null),
    StatsColumn("Success", 105.px, TsSort.SUCCESS, numeric = true),
    StatsColumn("Failed", 100.px, TsSort.FAILED, numeric = true),
    StatsColumn("Cancel", 105.px, TsSort.CANCELLED, numeric = true),
    StatsColumn("Retries", 100.px, TsSort.RETRIES, numeric = true),
    StatsColumn("Avg ms", 100.px, TsSort.AVG, numeric = true),
    StatsColumn("Min ms", 100.px, TsSort.MIN, numeric = true),
    StatsColumn("Max ms", 100.px, TsSort.MAX, numeric = true),
    StatsColumn("P95 ms", 100.px, TsSort.P95, numeric = true),
)

private fun Long?.fmt(): String = this?.toString() ?: "—"

private enum class TsSort { TYPE, QUEUE, SUCCESS, FAILED, CANCELLED, RETRIES, AVG, MIN, MAX, P95 }

// Text columns sort A→Z by default; counts/durations highest-first. Null durations sort last on desc.
private fun naturalAscending(key: TsSort): Boolean = key == TsSort.TYPE || key == TsSort.QUEUE

private fun comparatorFor(key: TsSort): Comparator<TypeStatsDto> = when (key) {
    TsSort.TYPE -> compareBy { it.payloadType }
    TsSort.QUEUE -> compareBy { it.queue }
    TsSort.SUCCESS -> compareBy { it.successCount }
    TsSort.FAILED -> compareBy { it.failedCount }
    TsSort.CANCELLED -> compareBy { it.cancelledCount }
    TsSort.RETRIES -> compareBy { it.retryCount }
    TsSort.AVG -> compareBy { it.avgDurationMs }
    TsSort.MIN -> compareBy { it.minDurationMs }
    TsSort.MAX -> compareBy { it.maxDurationMs }
    TsSort.P95 -> compareBy { it.p95DurationMs }
}
