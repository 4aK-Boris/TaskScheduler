package cs.trade.scheduler.dashboard.web.presentation.screens.workers

import cs.trade.scheduler.core.frontend.react.useValue
import cs.trade.scheduler.core.frontend.theme.SchedulerColors
import cs.trade.scheduler.core.frontend.theme.SchedulerText
import cs.trade.scheduler.core.frontend.ui.Button
import cs.trade.scheduler.core.frontend.ui.DataTable
import cs.trade.scheduler.core.frontend.ui.Dot
import cs.trade.scheduler.core.frontend.ui.EmptyState
import cs.trade.scheduler.core.frontend.ui.TableBody
import cs.trade.scheduler.core.frontend.ui.TableCell
import cs.trade.scheduler.core.frontend.ui.TableHead
import cs.trade.scheduler.core.frontend.ui.TableHeaderCell
import cs.trade.scheduler.core.frontend.ui.TableMessageRow
import cs.trade.scheduler.core.frontend.ui.TableRow
import cs.trade.scheduler.core.frontend.ui.ErrorBanner
import cs.trade.scheduler.core.frontend.ui.flexColumn
import cs.trade.scheduler.core.frontend.ui.flexRow
import cs.trade.scheduler.dashboard.web.presentation.components.CopyableText
import cs.trade.scheduler.dashboard.web.presentation.components.ListScreen
import cs.trade.scheduler.dashboard.web.presentation.components.SettingsMenu
import cs.trade.scheduler.dashboard.web.presentation.components.SkeletonRows
import cs.trade.scheduler.dashboard.web.presentation.components.SortableHeaderCell
import cs.trade.scheduler.dashboard.web.presentation.components.formatDateTime
import cs.trade.scheduler.dashboard.web.presentation.components.timeAgo
import cs.trade.scheduler.dashboard.web.presentation.components.useTableSort
import cs.trade.scheduler.shared.dto.WorkerDto
import emotion.react.css
import react.FC
import react.Key
import react.Props
import react.create
import react.dom.html.ReactHTML.div
import react.dom.html.ReactHTML.span
import react.useMemo
import web.cssom.px

/**
 * Worker roster: one row per node reporting a heartbeat, with its in-flight load and liveness.
 *
 * The whole roster arrives in one response (there are tens of workers, not thousands), so sorting
 * is client-side — no round-trip per column click.
 */
external interface WorkersContentProps : Props {
    var component: WorkersComponent
}

public val WorkersContent: FC<WorkersContentProps> = FC { props ->
    val component = props.component
    val state = useValue(component.model)
    val sort = useTableSort(WkSort.NODE, ::naturalAscending)
    val sortedWorkers = useMemo(state.items, sort.key, sort.ascending) {
        sort.sort(state.items, ::comparatorFor)
    }

    ListScreen {
        title = "Workers"
        count = state.items.size.toLong()
        actions = WorkersActions.create { this.component = component }

        DataTable {
            TableHead {
                react.dom.html.ReactHTML.tr {
                    COLUMNS.forEach { column ->
                        if (column.sortKey != null) {
                            SortableHeaderCell {
                                key = Key(column.label)
                                label = column.label
                                active = sort.isActive(column.sortKey)
                                direction = sort.directionOf(column.sortKey)
                                onClick = sort.onSort(column.sortKey)
                                width = column.width?.px
                            }
                        } else {
                            TableHeaderCell {
                                key = Key(column.label)
                                width = column.width?.px
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
                            rows = 6
                            widths = listOf(80.px, 210.px, 170.px, 170.px, 80.px, 90.px)
                        }
                    }

                    state.error != null -> TableMessageRow {
                        columns = COLUMNS.size
                        div {
                            css { padding = 16.px }
                            ErrorBanner {
                                message = "Error: ${state.error}"
                                onRetry = component::onRefreshClicked
                            }
                        }
                    }

                    state.items.isEmpty() -> TableMessageRow {
                        columns = COLUMNS.size
                        EmptyState {
                            title = "No workers reporting"
                            hint = "A worker registers here once it sends its first heartbeat."
                        }
                    }

                    else -> sortedWorkers.forEach { worker ->
                        WorkerRow {
                            key = Key(worker.nodeId)
                            this.worker = worker
                            timeAbsolute = state.timeAbsolute
                        }
                    }
                }
            }
        }
    }
}

private external interface WorkersActionsProps : Props {
    var component: WorkersComponent
}

private val WorkersActions: FC<WorkersActionsProps> = FC { props ->
    val component = props.component
    val state = useValue(component.model)

    SettingsMenu {
        autoRefreshSeconds = state.autoRefreshSeconds
        onAutoRefreshChanged = component::onAutoRefreshChanged
        timeSectionLabel = "Last HB / Uptime"
        relativeLabel = "Relative (3m ago)"
        timeAbsolute = state.timeAbsolute
        onTimeModeChanged = component::onTimeModeChanged
    }
    Button {
        onClick = component::onBackClicked
        +"Back"
    }
    Button {
        onClick = component::onRefreshClicked
        +"Refresh"
    }
}

private external interface WorkerRowProps : Props {
    var worker: WorkerDto
    var timeAbsolute: Boolean
}

private val WorkerRow: FC<WorkerRowProps> = FC { props ->
    val worker = props.worker
    val absolute = props.timeAbsolute

    TableRow {
        TableCell {
            div {
                css { flexRow(gap = 6.px) }
                Dot { color = if (worker.alive) SchedulerColors.success else SchedulerColors.error }
                span {
                    css {
                        +SchedulerText.labelMedium
                        color = if (worker.alive) SchedulerColors.onSurface else SchedulerColors.error
                    }
                    +if (worker.alive) "Alive" else "Dead"
                }
            }
        }
        TableCell {
            CopyableText {
                text = worker.nodeId
                style = SchedulerText.mono
            }
        }
        TableCell {
            CopyableText { text = worker.host }
        }
        TableCell {
            span {
                css { color = SchedulerColors.onSurfaceVariant }
                +if (worker.tags.isEmpty()) "—" else worker.tags.joinToString(", ")
            }
        }
        TableCell {
            InFlightCell {
                total = worker.inFlightCount
                byQueue = worker.inFlightByQueue
            }
        }
        TableCell {
            nowrap = true
            span {
                css { color = if (worker.alive) SchedulerColors.onSurface else SchedulerColors.error }
                +if (absolute) formatDateTime(worker.lastHeartbeat) else timeAgo(worker.lastHeartbeat)
            }
        }
        TableCell {
            nowrap = true
            span {
                css { color = SchedulerColors.onSurfaceVariant }
                +if (absolute) formatDateTime(worker.startedAt) else timeAgo(worker.startedAt)
            }
        }
    }
}

private external interface InFlightCellProps : Props {
    var total: Int
    var byQueue: Map<String, Int>
}

private val InFlightCell: FC<InFlightCellProps> = FC { props ->
    // Pre-V2 workers (or ones idle since restart) come through with an empty map — show the total.
    val active = props.byQueue.filterValues { it > 0 }
    div {
        css { flexColumn(gap = 2.px) }
        span { +props.total.toString() }
        if (active.isNotEmpty()) {
            span {
                css {
                    +SchedulerText.mono
                    color = SchedulerColors.onSurfaceVariant
                }
                +active.entries.sortedByDescending { it.value }.joinToString(", ") { "${it.key}=${it.value}" }
            }
        }
    }
}

private enum class WkSort { STATUS, NODE, HOST, INFLIGHT, LASTHB, UPTIME }

private class WorkerColumn(val label: String, val width: Int?, val sortKey: WkSort?)

private val COLUMNS: List<WorkerColumn> = listOf(
    WorkerColumn("Status", 120, WkSort.STATUS),
    WorkerColumn("Node ID", 260, WkSort.NODE),
    WorkerColumn("Host", 200, WkSort.HOST),
    // Tags is a list — not a useful single sort key. It also takes the flexible width.
    WorkerColumn("Tags", null, null),
    WorkerColumn("In flight", 180, WkSort.INFLIGHT),
    WorkerColumn("Last HB", 195, WkSort.LASTHB),
    WorkerColumn("Uptime", 195, WkSort.UPTIME),
)

// Text columns ascending; status (alive-first), in-flight and last-HB descending;
// uptime ascending = longest-running first.
private fun naturalAscending(key: WkSort): Boolean = when (key) {
    WkSort.NODE, WkSort.HOST, WkSort.UPTIME -> true
    else -> false
}

private fun comparatorFor(key: WkSort): Comparator<WorkerDto> = when (key) {
    WkSort.STATUS -> compareBy { it.alive }
    WkSort.NODE -> compareBy { it.nodeId }
    WkSort.HOST -> compareBy { it.host }
    WkSort.INFLIGHT -> compareBy { it.inFlightCount }
    WkSort.LASTHB -> compareBy { it.lastHeartbeat }
    WkSort.UPTIME -> compareBy { it.startedAt }
}
