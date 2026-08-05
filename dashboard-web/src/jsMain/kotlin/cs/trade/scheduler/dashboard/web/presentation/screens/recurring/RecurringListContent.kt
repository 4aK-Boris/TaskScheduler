package cs.trade.scheduler.dashboard.web.presentation.screens.recurring

import cs.trade.scheduler.core.frontend.react.useValue
import cs.trade.scheduler.core.frontend.theme.SchedulerColors
import cs.trade.scheduler.core.frontend.theme.SchedulerRadius
import cs.trade.scheduler.core.frontend.theme.SchedulerText
import cs.trade.scheduler.core.frontend.ui.Button
import cs.trade.scheduler.core.frontend.ui.ButtonSize
import cs.trade.scheduler.core.frontend.ui.DataTable
import cs.trade.scheduler.core.frontend.ui.EmptyState
import cs.trade.scheduler.core.frontend.ui.ErrorBanner
import cs.trade.scheduler.core.frontend.ui.Switch
import cs.trade.scheduler.core.frontend.ui.TableBody
import cs.trade.scheduler.core.frontend.ui.TableCell
import cs.trade.scheduler.core.frontend.ui.TableHead
import cs.trade.scheduler.core.frontend.ui.TableHeaderCell
import cs.trade.scheduler.core.frontend.ui.TableMessageRow
import cs.trade.scheduler.core.frontend.ui.TableRow
import cs.trade.scheduler.core.frontend.ui.TextInput
import cs.trade.scheduler.core.frontend.ui.flexColumn
import cs.trade.scheduler.core.frontend.ui.flexRow
import cs.trade.scheduler.dashboard.web.presentation.components.CopyableText
import cs.trade.scheduler.dashboard.web.presentation.components.ListScreen
import cs.trade.scheduler.dashboard.web.presentation.components.SettingsMenu
import cs.trade.scheduler.dashboard.web.presentation.components.SkeletonRows
import cs.trade.scheduler.dashboard.web.presentation.components.SortableHeaderCell
import cs.trade.scheduler.dashboard.web.presentation.components.StateChip
import cs.trade.scheduler.dashboard.web.presentation.components.formatDateTime
import cs.trade.scheduler.dashboard.web.presentation.components.timeAgo
import cs.trade.scheduler.dashboard.web.presentation.components.useTableSort
import cs.trade.scheduler.shared.dto.RecurringJobDto
import cs.trade.scheduler.shared.dto.RecurringRunDto
import emotion.react.css
import react.FC
import react.Key
import react.Props
import react.create
import react.dom.html.ReactHTML.div
import react.dom.html.ReactHTML.span
import react.dom.html.ReactHTML.tr
import react.useMemo
import react.useState
import web.cssom.Color
import web.cssom.Overflow
import web.cssom.Padding
import web.cssom.pct
import web.cssom.px
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Instant

/**
 * Registered recurring definitions: their cron, when they next fire, and whether they're enabled.
 *
 * Search and sort are both client-side — the roster is small and fully loaded, so filtering it
 * server-side would add a round-trip per keystroke for no benefit.
 */
external interface RecurringListContentProps : Props {
    var component: RecurringListComponent
}

public val RecurringListContent: FC<RecurringListContentProps> = FC { props ->
    val component = props.component
    val state = useValue(component.model)
    val sort = useTableSort(RcSort.NEXT, ::naturalAscending)
    var search by useState("")
    val needle = search.trim()

    val visibleItems = useMemo(state.items, sort.key, sort.ascending, needle) {
        val filtered = if (needle.isEmpty()) {
            state.items
        } else {
            state.items.filter {
                // contains() over the full payload type also matches the short name shown in the
                // Payload column (it's a suffix of the fully-qualified type).
                it.id.contains(needle, ignoreCase = true) ||
                    it.payloadType.contains(needle, ignoreCase = true)
            }
        }
        sort.sort(filtered, ::comparatorFor)
    }

    ListScreen {
        // Count reflects what's visible — a live "N matches" while filtering.
        title = "Recurring"
        count = visibleItems.size.toLong()
        actions = RecurringActions.create { this.component = component }

        div {
            css {
                flexRow()
                padding = Padding(12.px, 16.px)
            }
            TextInput {
                value = search
                placeholder = "Search by name or type"
                width = 280.px
                onValueChange = { search = it }
            }
            if (needle.isNotEmpty()) {
                Button {
                    size = ButtonSize.SMALL
                    onClick = { search = "" }
                    +"Clear"
                }
            }
        }

        DataTable {
            TableHead {
                tr {
                    SortableHeaderCell {
                        label = "ID"
                        active = sort.isActive(RcSort.ID)
                        direction = sort.directionOf(RcSort.ID)
                        onClick = sort.onSort(RcSort.ID)
                    }
                    SortableHeaderCell {
                        label = "Cron"
                        width = 175.px
                        active = sort.isActive(RcSort.CRON)
                        direction = sort.directionOf(RcSort.CRON)
                        onClick = sort.onSort(RcSort.CRON)
                    }
                    SortableHeaderCell {
                        label = "Queue"
                        width = 130.px
                        active = sort.isActive(RcSort.QUEUE)
                        direction = sort.directionOf(RcSort.QUEUE)
                        onClick = sort.onSort(RcSort.QUEUE)
                    }
                    SortableHeaderCell {
                        label = "Payload"
                        width = 230.px
                        active = sort.isActive(RcSort.PAYLOAD)
                        direction = sort.directionOf(RcSort.PAYLOAD)
                        onClick = sort.onSort(RcSort.PAYLOAD)
                    }
                    SortableHeaderCell {
                        label = "Next"
                        width = 195.px
                        active = sort.isActive(RcSort.NEXT)
                        direction = sort.directionOf(RcSort.NEXT)
                        onClick = sort.onSort(RcSort.NEXT)
                    }
                    SortableHeaderCell {
                        label = "Last"
                        width = 195.px
                        active = sort.isActive(RcSort.LAST)
                        direction = sort.directionOf(RcSort.LAST)
                        onClick = sort.onSort(RcSort.LAST)
                    }
                    // Not sortable: it reflects a joined-in run, not a column of the definition.
                    TableHeaderCell {
                        width = 210.px
                        +"Last run"
                    }
                    SortableHeaderCell {
                        label = "Status"
                        width = 115.px
                        active = sort.isActive(RcSort.STATUS)
                        direction = sort.directionOf(RcSort.STATUS)
                        onClick = sort.onSort(RcSort.STATUS)
                    }
                    TableHeaderCell {
                        width = 120.px
                        +"Run"
                    }
                }
            }
            TableBody {
                when {
                    state.loading && state.items.isEmpty() -> TableMessageRow {
                        columns = COLUMN_COUNT
                        SkeletonRows {
                            rows = 8
                            widths = listOf(200.px, 120.px, 90.px, 240.px, 90.px, 90.px)
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
                            title = "No recurring jobs registered"
                            hint = "Definitions appear here once the app registers them at startup."
                        }
                    }

                    visibleItems.isEmpty() -> TableMessageRow {
                        columns = COLUMN_COUNT
                        EmptyState { title = "No recurring jobs match \"$needle\"" }
                    }

                    else -> visibleItems.forEach { row ->
                        RecurringRow {
                            key = Key(row.id)
                            job = row
                            busy = state.togglingId == row.id
                            triggering = state.triggeringId == row.id
                            runEnabled = state.triggeringId == null
                            ageAbsolute = state.ageAbsolute
                            onToggle = { enable -> component.onToggleClicked(row.id, enable) }
                            onRun = { component.onRunNowClicked(row.id) }
                            onOpenRun = row.lastRun?.let { run -> { component.onRunClicked(run.jobId) } }
                        }
                    }
                }
            }
        }
    }
}

private external interface RecurringActionsProps : Props {
    var component: RecurringListComponent
}

private val RecurringActions: FC<RecurringActionsProps> = FC { props ->
    val component = props.component
    val state = useValue(component.model)

    SettingsMenu {
        autoRefreshSeconds = state.autoRefreshSeconds
        onAutoRefreshChanged = component::onAutoRefreshChanged
        timeSectionLabel = "Next / Last columns"
        relativeLabel = "Relative (2h ago)"
        timeAbsolute = state.ageAbsolute
        onTimeModeChanged = component::onAgeModeChanged
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

private external interface RecurringRowProps : Props {
    var job: RecurringJobDto
    var busy: Boolean
    var triggering: Boolean
    var runEnabled: Boolean
    var ageAbsolute: Boolean
    var onToggle: (Boolean) -> Unit
    var onRun: () -> Unit

    /** Opens the definition's current-or-last run; `null` when it has never fired. */
    var onOpenRun: (() -> Unit)?
}

private val RecurringRow: FC<RecurringRowProps> = FC { props ->
    val job = props.job
    // Disabled rows read muted so the operator can scan which definitions are paused.
    val fg: Color = if (job.enabled) SchedulerColors.onSurface else SchedulerColors.onSurfaceVariant

    TableRow {
        // Only clickable when the definition has actually run — otherwise there is nothing to open.
        props.onOpenRun?.let { open -> onClick = open }

        TableCell {
            CopyableText {
                text = job.id
                color = fg
            }
        }
        TableCell {
            span {
                css {
                    +SchedulerText.mono
                    color = fg
                }
                +job.cron
            }
        }
        TableCell {
            span {
                css { color = fg }
                +job.queue
            }
        }
        TableCell {
            CopyableText {
                text = job.payloadType.substringAfterLast('.')
                copyValue = job.payloadType
                tooltip = job.payloadType
                color = fg
            }
        }
        TableCell {
            nowrap = true
            span {
                css { color = fg }
                +if (props.ageAbsolute) formatDateTime(job.nextTriggerAt) else timeAgoOrSoon(job.nextTriggerAt)
            }
        }
        TableCell {
            nowrap = true
            span {
                css { color = SchedulerColors.onSurfaceVariant }
                +(job.lastTriggeredAt?.let { if (props.ageAbsolute) formatDateTime(it) else timeAgo(it) } ?: "never")
            }
        }
        TableCell {
            LastRunCell { run = job.lastRun }
        }
        TableCell {
            // The row opens the run; the switch must only toggle.
            div {
                onClick = { event -> event.stopPropagation() }
                Switch {
                    checked = job.enabled
                disabled = props.busy
                    title = if (job.enabled) "Disable this definition" else "Enable this definition"
                    onCheckedChange = props.onToggle
                }
            }
        }
        TableCell {
            // Manual one-off fire — independent of the enabled switch, so even a paused
            // definition can be run on demand.
            div {
                onClick = { event -> event.stopPropagation() }
                Button {
                    size = ButtonSize.SMALL
                    disabled = !props.runEnabled
                    onClick = props.onRun
                    +if (props.triggering) "Running…" else "Run"
                }
            }
        }
    }
}

private external interface LastRunCellProps : Props {
    var run: RecurringRunDto?
}

/**
 * What this definition is doing right now: the state of its live run, or of the last one that
 * finished. A running job also shows its progress, so an operator can see how far along it is
 * without opening it.
 */
private val LastRunCell: FC<LastRunCellProps> = FC { props ->
    val run = props.run
    if (run == null) {
        span {
            css { color = SchedulerColors.onSurfaceVariant }
            +"never run"
        }
    } else {
        div {
            css { flexColumn(gap = 6.px) }
            div {
                css { flexRow(gap = 8.px) }
                StateChip { state = run.state }
                if (run.isLive) {
                    // A definition can be mid-run for a long time; "how long" is the useful part.
                    run.startedAt?.let { started ->
                        span {
                            css { color = SchedulerColors.onSurfaceVariant }
                            +timeAgo(started)
                        }
                    }
                }
            }
            if (run.isLive) {
                RunProgress { this.run = run }
            }
        }
    }
}

private external interface RunProgressProps : Props {
    var run: RecurringRunDto
}

/**
 * Progress strip for a live run — green/red split when the handler reported counts, a single
 * cobalt fill when it only reported a fraction, and nothing at all when it reported neither.
 * Widths transition so the bar grows instead of jumping between refreshes.
 */
private val RunProgress: FC<RunProgressProps> = FC { props ->
    val run = props.run
    val succeeded = run.progressSucceeded
    val failed = run.progressFailed
    val total = run.progressTotal
    val counting = succeeded != null && failed != null && total != null && total > 0L
    val fraction = run.progress?.toDouble()

    if (counting || fraction != null) {
        div {
            css {
                flexRow()
                width = 100.pct
                height = 4.px
                borderRadius = SchedulerRadius.extraSmall
                overflow = Overflow.hidden
                backgroundColor = SchedulerColors.surfaceContainerHigh
            }
            if (counting) {
                val succeededFrac = (succeeded!!.toDouble() / total!!).coerceIn(0.0, 1.0)
                val failedFrac = (failed!!.toDouble() / total).coerceIn(0.0, 1.0 - succeededFrac)
                runSegment("succeeded", succeededFrac, SchedulerColors.success)
                runSegment("failed", failedFrac, SchedulerColors.error)
            } else {
                runSegment("progress", fraction!!.coerceIn(0.0, 1.0), SchedulerColors.primary)
            }
        }
    }
}

private fun react.ChildrenBuilder.runSegment(key: String, fraction: Double, fill: Color) {
    div {
        this.key = Key(key)
        css {
            width = (fraction * 100).pct
            height = 100.pct
            backgroundColor = fill
            asDynamic().transition = "width 0.45s cubic-bezier(0.4, 0, 0.2, 1)"
        }
    }
}

private const val COLUMN_COUNT = 9

/** Next-run column: future reads "in 5m", a missed/overdue run falls back to "3m ago". */
private fun timeAgoOrSoon(instant: Instant): String {
    val now = Clock.System.now()
    val delta = instant - now
    return if (delta.isPositive()) "in ${formatDuration(delta)}" else timeAgo(instant, now)
}

private fun formatDuration(d: Duration): String = when {
    d.inWholeMinutes < 1 -> "${d.inWholeSeconds}s"
    d.inWholeHours < 1 -> "${d.inWholeMinutes}m"
    d.inWholeDays < 1 -> "${d.inWholeHours}h"
    else -> "${d.inWholeDays}d"
}

private enum class RcSort { ID, CRON, QUEUE, PAYLOAD, NEXT, LAST, STATUS }

// Text + Next sort ascending by default; Last (most-recent) and Status (enabled-first) descending.
private fun naturalAscending(key: RcSort): Boolean = key != RcSort.LAST && key != RcSort.STATUS

private fun comparatorFor(key: RcSort): Comparator<RecurringJobDto> = when (key) {
    RcSort.ID -> compareBy { it.id }
    RcSort.CRON -> compareBy { it.cron }
    RcSort.QUEUE -> compareBy { it.queue }
    RcSort.PAYLOAD -> compareBy { it.payloadType }
    RcSort.NEXT -> compareBy { it.nextTriggerAt }
    RcSort.LAST -> compareBy { it.lastTriggeredAt }
    RcSort.STATUS -> compareBy { it.enabled }
}
