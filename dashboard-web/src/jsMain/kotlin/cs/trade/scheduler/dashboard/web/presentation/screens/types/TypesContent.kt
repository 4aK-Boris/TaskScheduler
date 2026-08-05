package cs.trade.scheduler.dashboard.web.presentation.screens.types

import cs.trade.scheduler.core.frontend.react.useValue
import cs.trade.scheduler.core.frontend.theme.SchedulerColors
import cs.trade.scheduler.core.frontend.theme.SchedulerRadius
import cs.trade.scheduler.core.frontend.theme.SchedulerText
import cs.trade.scheduler.core.frontend.ui.Button
import cs.trade.scheduler.core.frontend.ui.ButtonSize
import cs.trade.scheduler.core.frontend.ui.ButtonVariant
import cs.trade.scheduler.core.frontend.ui.DataTable
import cs.trade.scheduler.core.frontend.ui.EmptyState
import cs.trade.scheduler.core.frontend.ui.TableBody
import cs.trade.scheduler.core.frontend.ui.TableCell
import cs.trade.scheduler.core.frontend.ui.TableHead
import cs.trade.scheduler.core.frontend.ui.TableHeaderCell
import cs.trade.scheduler.core.frontend.ui.TableMessageRow
import cs.trade.scheduler.core.frontend.ui.TableRow
import cs.trade.scheduler.core.frontend.ui.TextInput
import cs.trade.scheduler.core.frontend.ui.flexColumn
import cs.trade.scheduler.core.frontend.ui.flexRow
import cs.trade.scheduler.dashboard.web.presentation.components.AutocompleteInput
import cs.trade.scheduler.dashboard.web.presentation.components.CopyableText
import cs.trade.scheduler.dashboard.web.presentation.components.ListScreen
import cs.trade.scheduler.dashboard.web.presentation.components.PausedBadge
import cs.trade.scheduler.dashboard.web.presentation.components.SettingsMenu
import cs.trade.scheduler.dashboard.web.presentation.components.SkeletonRows
import cs.trade.scheduler.dashboard.web.presentation.components.SortableHeaderCell
import cs.trade.scheduler.dashboard.web.presentation.components.formatDateTime
import cs.trade.scheduler.dashboard.web.presentation.components.timeAgo
import cs.trade.scheduler.dashboard.web.presentation.components.useTableSort
import cs.trade.scheduler.shared.dto.TypePauseDto
import emotion.react.css
import react.FC
import react.Key
import react.Props
import react.create
import react.dom.html.ReactHTML.div
import react.dom.html.ReactHTML.span
import react.dom.html.ReactHTML.tr
import react.useMemo
import web.cssom.AlignItems
import web.cssom.Border
import web.cssom.LineStyle
import web.cssom.Padding
import web.cssom.TextAlign
import web.cssom.TextTransform
import web.cssom.number
import web.cssom.pct
import web.cssom.px

/**
 * Paused payload types: the pause composer on top, the current pauses below.
 *
 * Pausing a type stops workers from picking up its jobs without touching the jobs themselves —
 * the operator's blast-radius control when one handler misbehaves.
 */
external interface TypesContentProps : Props {
    var component: TypesComponent
}

public val TypesContent: FC<TypesContentProps> = FC { props ->
    val component = props.component
    val state = useValue(component.model)
    val sort = useTableSort(TpSort.SINCE, ::naturalAscending)
    val sortedItems = useMemo(state.items, sort.key, sort.ascending) {
        sort.sort(state.items, ::comparatorFor)
    }

    ListScreen {
        title = "Paused Types"
        count = state.items.size.toLong()
        actions = TypesActions.create { this.component = component }

        PauseForm {
            typeValue = state.pauseFormType
            reasonValue = state.pauseFormReason
            submitting = state.submitting
            error = state.error
            knownTypes = state.knownTypes
            pausedTypes = state.items.map { it.payloadType }.toSet()
            onTypeChange = component::onPauseFormTypeChanged
            onReasonChange = component::onPauseFormReasonChanged
            onSubmit = component::onPauseSubmit
        }

        DataTable {
            TableHead {
                tr {
                    SortableHeaderCell {
                        label = "Payload Type"
                        active = sort.isActive(TpSort.TYPE)
                        direction = sort.directionOf(TpSort.TYPE)
                        onClick = sort.onSort(TpSort.TYPE)
                    }
                    SortableHeaderCell {
                        label = "Since"
                        width = 170.px
                        active = sort.isActive(TpSort.SINCE)
                        direction = sort.directionOf(TpSort.SINCE)
                        onClick = sort.onSort(TpSort.SINCE)
                    }
                    SortableHeaderCell {
                        label = "Paused By"
                        width = 200.px
                        active = sort.isActive(TpSort.BY)
                        direction = sort.directionOf(TpSort.BY)
                        onClick = sort.onSort(TpSort.BY)
                    }
                    SortableHeaderCell {
                        label = "Reason"
                        width = 300.px
                        active = sort.isActive(TpSort.REASON)
                        direction = sort.directionOf(TpSort.REASON)
                        onClick = sort.onSort(TpSort.REASON)
                    }
                    // Last column is the Unpause action — not sortable.
                    TableHeaderCell { width = 130.px }
                }
            }
            TableBody {
                when {
                    state.loading && state.items.isEmpty() -> TableMessageRow {
                        columns = COLUMN_COUNT
                        SkeletonRows {
                            rows = 6
                            widths = listOf(280.px, 80.px, 120.px, 200.px, 90.px)
                        }
                    }

                    state.items.isEmpty() -> TableMessageRow {
                        columns = COLUMN_COUNT
                        EmptyState {
                            title = "No types currently paused"
                            hint = "Pause one above to stop workers picking up its jobs."
                        }
                    }

                    else -> sortedItems.forEach { row ->
                        PausedRow {
                            key = Key(row.payloadType)
                            item = row
                            busy = state.unpausingType == row.payloadType
                            timeAbsolute = state.timeAbsolute
                            onUnpause = { component.onUnpauseClicked(row.payloadType) }
                        }
                    }
                }
            }
        }
    }
}

private external interface TypesActionsProps : Props {
    var component: TypesComponent
}

private val TypesActions: FC<TypesActionsProps> = FC { props ->
    val component = props.component
    val state = useValue(component.model)

    SettingsMenu {
        autoRefreshSeconds = state.autoRefreshSeconds
        onAutoRefreshChanged = component::onAutoRefreshChanged
        timeSectionLabel = "Since column"
        relativeLabel = "Relative (2h ago)"
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

private external interface PauseFormProps : Props {
    var typeValue: String
    var reasonValue: String
    var submitting: Boolean
    var error: String?
    var knownTypes: List<String>
    var pausedTypes: Set<String>
    var onTypeChange: (String) -> Unit
    var onReasonChange: (String) -> Unit
    var onSubmit: () -> Unit
}

/**
 * "Pause a type" composer — a tinted strip at the top of the panel so it reads as input, not data.
 * The type field autocompletes against the known types; a free-typed FQN is still valid (pausing a
 * brand-new type before any job of it exists). The submit error renders inline beneath the row.
 */
private val PauseForm: FC<PauseFormProps> = FC { props ->
    div {
        css {
            flexColumn(gap = 10.px)
            padding = 16.px
            backgroundColor = SchedulerColors.surfaceContainerLow
            borderBottom = Border(1.px, LineStyle.solid, SchedulerColors.outlineVariant)
        }

        span {
            css {
                +SchedulerText.labelSmall
                textTransform = TextTransform.uppercase
                color = SchedulerColors.onSurfaceVariant
            }
            +"Pause a type"
        }

        div {
            css { flexRow(gap = 12.px, align = AlignItems.flexStart) }

            AutocompleteInput {
                value = props.typeValue
                placeholder = "Payload type (FQN)"
                suggestions = props.knownTypes
                monospace = true
                disabled = props.submitting
                onValueChange = props.onTypeChange
                onSubmit = props.onSubmit
                // Already-paused types aren't hidden from the list — re-pausing updates the row's
                // reason and actor — but they're marked so the operator knows.
                suggestionBadge = { suggestion ->
                    if (suggestion in props.pausedTypes) PausedBadge.create() else null
                }
            }

            TextInput {
                value = props.reasonValue
                placeholder = "Reason (optional)"
                disabled = props.submitting
                width = 320.px
                onValueChange = props.onReasonChange
                onSubmit = props.onSubmit
            }

            Button {
                variant = ButtonVariant.FILLED
                disabled = props.submitting || props.typeValue.isBlank()
                onClick = props.onSubmit
                +if (props.submitting) "Pausing…" else "Pause"
            }
        }

        props.error?.let { message ->
            span {
                css {
                    +SchedulerText.bodySmall
                    color = SchedulerColors.error
                }
                +message
            }
        }
    }
}

private external interface PausedRowProps : Props {
    var item: TypePauseDto
    var busy: Boolean
    var timeAbsolute: Boolean
    var onUnpause: () -> Unit
}

private val PausedRow: FC<PausedRowProps> = FC { props ->
    val row = props.item
    TableRow {
        TableCell {
            CopyableText {
                text = row.payloadType
                style = SchedulerText.mono
            }
        }
        TableCell {
            nowrap = true
            +if (props.timeAbsolute) formatDateTime(row.pausedSince) else timeAgo(row.pausedSince)
        }
        TableCell { +row.pausedBy }
        TableCell {
            span {
                css { color = if (row.reason == null) SchedulerColors.onSurfaceVariant else SchedulerColors.onSurface }
                +(row.reason ?: "—")
            }
        }
        TableCell {
            align = TextAlign.right
            Button {
                size = ButtonSize.SMALL
                disabled = props.busy
                onClick = props.onUnpause
                +if (props.busy) "…" else "Unpause"
            }
        }
    }
}

private const val COLUMN_COUNT = 5

private enum class TpSort { TYPE, SINCE, BY, REASON }

// Text columns ascending by default; Since (most recently paused) descending.
private fun naturalAscending(key: TpSort): Boolean = key != TpSort.SINCE

private fun comparatorFor(key: TpSort): Comparator<TypePauseDto> = when (key) {
    TpSort.TYPE -> compareBy { it.payloadType }
    TpSort.SINCE -> compareBy { it.pausedSince }
    TpSort.BY -> compareBy { it.pausedBy }
    TpSort.REASON -> compareBy { it.reason }
}
