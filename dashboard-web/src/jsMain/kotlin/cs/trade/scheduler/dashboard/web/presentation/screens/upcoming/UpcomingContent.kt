package cs.trade.scheduler.dashboard.web.presentation.screens.upcoming

import cs.trade.scheduler.core.frontend.react.useValue
import cs.trade.scheduler.core.frontend.theme.SchedulerColors
import cs.trade.scheduler.core.frontend.theme.SchedulerText
import cs.trade.scheduler.core.frontend.ui.Button
import cs.trade.scheduler.core.frontend.ui.ButtonSize
import cs.trade.scheduler.core.frontend.ui.ButtonVariant
import cs.trade.scheduler.core.frontend.ui.Chip
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
import cs.trade.scheduler.dashboard.web.presentation.components.SettingsMenu
import cs.trade.scheduler.dashboard.web.presentation.components.SkeletonRows
import cs.trade.scheduler.dashboard.web.presentation.components.StateChip
import cs.trade.scheduler.dashboard.web.presentation.components.formatDateTime
import cs.trade.scheduler.dashboard.web.presentation.components.timeUntil
import cs.trade.scheduler.shared.dto.UpcomingOccurrenceDto
import cs.trade.scheduler.shared.dto.UpcomingSource
import emotion.react.css
import react.FC
import react.Key
import react.Props
import react.create
import react.dom.html.ReactHTML.div
import react.dom.html.ReactHTML.span
import react.dom.html.ReactHTML.tr
import web.cssom.Padding
import web.cssom.px

/**
 * Agenda of everything due to run inside a look-ahead window, soonest first — future-scheduled
 * jobs and the next occurrences of recurring definitions in one list.
 *
 * Ordering and the window filter are the server's; this screen adds no client-side sort, because
 * "soonest first" is the only ordering an agenda has.
 */
external interface UpcomingContentProps : Props {
    var component: UpcomingComponent
}

public val UpcomingContent: FC<UpcomingContentProps> = FC { props ->
    val component = props.component
    val state = useValue(component.model)

    ListScreen {
        title = "Upcoming"
        count = state.items.size.toLong()
        actions = UpcomingActions.create { this.component = component }

        if (state.truncated) {
            div {
                css {
                    padding = Padding(6.px, 16.px)
                    +SchedulerText.bodySmall
                    color = SchedulerColors.onSurfaceVariant
                }
                +"Showing the soonest ${state.items.size} runs — narrow the window for fewer."
            }
        }

        DataTable {
            TableHead {
                tr {
                    TableHeaderCell {
                        width = 160.px
                        +"When"
                    }
                    TableHeaderCell {
                        width = 130.px
                        +"Kind"
                    }
                    TableHeaderCell {
                        width = 130.px
                        +"Queue"
                    }
                    TableHeaderCell { +"Name" }
                    TableHeaderCell {
                        width = 210.px
                        +"Cron / ID"
                    }
                }
            }
            TableBody {
                when {
                    state.loading && state.items.isEmpty() -> TableMessageRow {
                        columns = COLUMN_COUNT
                        SkeletonRows {
                            rows = 8
                            widths = listOf(110.px, 90.px, 90.px, 220.px, 120.px)
                        }
                    }

                    state.error != null -> TableMessageRow {
                        columns = COLUMN_COUNT
                        div {
                            css { padding = 16.px }
                            ErrorBanner {
                                message = "Error: ${state.error}"
                                onRetry = component::onRefreshClicked
                            }
                        }
                    }

                    state.items.isEmpty() -> TableMessageRow {
                        columns = COLUMN_COUNT
                        EmptyState {
                            title = "Nothing scheduled to run in the selected window"
                            hint = "Widen the window to look further ahead."
                        }
                    }

                    // (source, id, time) is unique — the same recurring repeats at distinct times.
                    else -> state.items.forEach { row ->
                        UpcomingRow {
                            key = Key("${row.source}|${row.id}|${row.at}")
                            item = row
                            timeAbsolute = state.timeAbsolute
                            onSelect = {
                                if (row.source == UpcomingSource.JOB) {
                                    component.onJobClicked(row.id)
                                } else {
                                    component.onRecurringClicked()
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

private external interface UpcomingActionsProps : Props {
    var component: UpcomingComponent
}

private val UpcomingActions: FC<UpcomingActionsProps> = FC { props ->
    val component = props.component
    val state = useValue(component.model)

    WindowSegments {
        current = state.windowMinutes
        onSelected = component::onWindowChanged
    }
    SettingsMenu {
        autoRefreshSeconds = state.autoRefreshSeconds
        onAutoRefreshChanged = component::onAutoRefreshChanged
        timeSectionLabel = "When column"
        relativeLabel = "Relative (in 18m)"
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

private external interface WindowSegmentsProps : Props {
    var current: Int
    var onSelected: (Int) -> Unit
}

/** Look-ahead window picker — no "Off"; this screen IS the agenda, the window only narrows it. */
private val WindowSegments: FC<WindowSegmentsProps> = FC { props ->
    div {
        css { flexRow(gap = 6.px) }
        WINDOW_OPTIONS.forEach { (label, minutes) ->
            Button {
                key = Key(label)
                size = ButtonSize.SMALL
                variant = if (props.current == minutes) ButtonVariant.FILLED else ButtonVariant.OUTLINED
                onClick = { props.onSelected(minutes) }
                +label
            }
        }
    }
}

private val WINDOW_OPTIONS: List<Pair<String, Int>> =
    listOf("1h" to 60, "6h" to 360, "24h" to 1440, "3d" to 4320)

private external interface UpcomingRowProps : Props {
    var item: UpcomingOccurrenceDto
    var timeAbsolute: Boolean
    var onSelect: () -> Unit
}

private val UpcomingRow: FC<UpcomingRowProps> = FC { props ->
    val item = props.item
    TableRow {
        onClick = props.onSelect

        TableCell {
            nowrap = true
            // The headline value — when this run fires. Future → "in 18m"; absolute on toggle.
            +if (props.timeAbsolute) formatDateTime(item.at) else timeUntil(item.at)
        }
        TableCell {
            // A real job shows its state chip, so kind and state read at once; a recurring
            // occurrence has no job row yet and gets a cobalt "RECURRING" pill instead.
            val state = item.state
            if (item.source == UpcomingSource.JOB && state != null) {
                StateChip { this.state = state }
            } else {
                Chip {
                    container = SchedulerColors.primaryContainer
                    content = SchedulerColors.onPrimaryContainer
                    +"RECURRING"
                }
            }
        }
        TableCell { +item.queue }
        TableCell {
            CopyableText {
                text = item.payloadType.substringAfterLast('.')
                copyValue = item.payloadType
                tooltip = item.payloadType
            }
        }
        TableCell {
            nowrap = true
            span {
                css {
                    +SchedulerText.mono
                    color = SchedulerColors.onSurfaceVariant
                }
                // Recurring → its cron; one-off job → short id.
                +(item.cron ?: item.id.take(8))
            }
        }
    }
}

private const val COLUMN_COUNT = 5
