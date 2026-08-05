package cs.trade.scheduler.dashboard.web.presentation.screens.joblist

import cs.trade.scheduler.core.frontend.react.useValue
import cs.trade.scheduler.core.frontend.theme.SchedulerColors
import cs.trade.scheduler.core.frontend.theme.SchedulerRadius
import cs.trade.scheduler.core.frontend.theme.SchedulerText
import cs.trade.scheduler.core.frontend.ui.Button
import cs.trade.scheduler.core.frontend.ui.ButtonSize
import cs.trade.scheduler.core.frontend.ui.ButtonVariant
import cs.trade.scheduler.core.frontend.ui.Checkbox
import cs.trade.scheduler.core.frontend.ui.DataTable
import cs.trade.scheduler.core.frontend.ui.EmptyState
import cs.trade.scheduler.core.frontend.ui.ErrorBanner
import cs.trade.scheduler.core.frontend.ui.IconButton
import cs.trade.scheduler.core.frontend.ui.ChevronLeftIcon
import cs.trade.scheduler.core.frontend.ui.ChevronRightIcon
import cs.trade.scheduler.core.frontend.ui.TableBody
import cs.trade.scheduler.core.frontend.ui.TableCell
import cs.trade.scheduler.core.frontend.ui.TableHead
import cs.trade.scheduler.core.frontend.ui.TableHeaderCell
import cs.trade.scheduler.core.frontend.ui.TableMessageRow
import cs.trade.scheduler.core.frontend.ui.TableRow
import cs.trade.scheduler.core.frontend.ui.ToggleChip
import cs.trade.scheduler.core.frontend.ui.ellipsis
import cs.trade.scheduler.core.frontend.ui.flexColumn
import cs.trade.scheduler.core.frontend.ui.flexRow
import cs.trade.scheduler.dashboard.web.presentation.components.AutocompleteInput
import cs.trade.scheduler.dashboard.web.presentation.components.CopyableText
import cs.trade.scheduler.dashboard.web.presentation.components.ListScreen
import cs.trade.scheduler.dashboard.web.presentation.components.PausedBadge
import cs.trade.scheduler.dashboard.web.presentation.components.QueueHealthBadge
import cs.trade.scheduler.dashboard.web.presentation.components.SettingsMenu
import cs.trade.scheduler.dashboard.web.presentation.components.SkeletonRows
import cs.trade.scheduler.dashboard.web.presentation.components.SortDirection
import cs.trade.scheduler.dashboard.web.presentation.components.SortableHeaderCell
import cs.trade.scheduler.dashboard.web.presentation.components.StateChip
import cs.trade.scheduler.dashboard.web.presentation.components.formatDateTime
import cs.trade.scheduler.dashboard.web.presentation.components.timeAgo
import cs.trade.scheduler.shared.JobSortField
import cs.trade.scheduler.shared.JobState
import cs.trade.scheduler.shared.dto.BulkActionResponse
import cs.trade.scheduler.shared.dto.JobView
import cs.trade.scheduler.shared.dto.QueueHealthDto
import cs.trade.scheduler.shared.dto.QueueHealthStatus
import emotion.react.css
import react.FC
import react.Key
import react.Props
import react.create
import react.dom.html.ReactHTML.div
import react.dom.html.ReactHTML.span
import react.dom.html.ReactHTML.tr
import web.cssom.AlignItems
import web.cssom.Border
import web.cssom.Color
import web.cssom.FlexWrap
import web.cssom.JustifyContent
import web.cssom.Length
import web.cssom.LineStyle
import web.cssom.Overflow
import web.cssom.Padding
import web.cssom.number
import web.cssom.pct
import web.cssom.px

/**
 * The dashboard's main view: a filtered, sorted, paginated table of jobs with bulk actions.
 *
 * Everything here is server-driven — filters, sort and paging all round-trip through the
 * component, because the table can hold millions of rows and only a page of them is ever loaded.
 * (Contrast with Workers / Types, which sort client-side over a fully-loaded roster.)
 */
external interface JobListContentProps : Props {
    var component: JobListComponent
}

public val JobListContent: FC<JobListContentProps> = FC { props ->
    val component = props.component
    val model = useValue(component.model)
    val allVisibleSelected = model.items.isNotEmpty() && model.items.all { it.id in model.selectedIds }

    ListScreen {
        title = "Jobs"
        count = model.total
        actions = JobListActions.create { this.component = component }

        StateFilterRow {
            selected = model.stateFilter
            dlqOnly = model.dlqOnly
            onChanged = component::onStateFilterChanged
            onDlqToggle = component::onDlqOnlyToggled
        }

        div {
            css {
                flexRow(gap = 12.px, align = AlignItems.flexStart)
                padding = Padding(4.px, 16.px)
            }
            AutocompleteInput {
                value = model.queueFilter
                placeholder = "Queue"
                suggestions = model.queueHealth.map { it.queue }.distinct().sorted()
                width = 220.px
                onValueChange = component::onQueueFilterChanged
            }
            AutocompleteInput {
                value = model.payloadTypeFilter
                placeholder = "Task Type"
                suggestions = model.knownTypes
                width = 320.px
                monospace = true
                onValueChange = component::onPayloadTypeFilterChanged
            }
        }

        QueueHealthRow { health = model.queueHealth }

        if (model.selectedIds.isNotEmpty() || model.bulkResult != null || model.bulkInFlight) {
            BulkActionToolbar {
                selectedCount = model.selectedIds.size
                inFlight = model.bulkInFlight
                actionLabel = model.bulkActionLabel
                result = model.bulkResult
                onRetry = component::onBulkRetryClicked
                onCancel = component::onBulkCancelClicked
                onDelete = component::onBulkDeleteClicked
                onClear = component::onClearSelection
                onDismiss = component::onDismissBulkResult
            }
        }

        PaginationBar {
            page = model.page
            pageSize = model.pageSize
            total = model.total
            loading = model.loading
            onPrev = component::onPrevPageClicked
            onNext = component::onNextPageClicked
            onSizeChange = component::onPageSizeChanged
        }

        DataTable {
            TableHead {
                tr {
                    TableHeaderCell {
                        width = 44.px
                        Checkbox {
                            checked = allVisibleSelected
                            // Header box reads "some, not all" while a partial selection stands.
                            indeterminate = !allVisibleSelected && model.selectedIds.isNotEmpty()
                            onCheckedChange = { component.onSelectAllVisibleClicked(!allVisibleSelected) }
                        }
                    }
                    SORTABLE_COLUMNS.forEach { column ->
                        SortableHeaderCell {
                            key = Key(column.label)
                            label = column.label
                            width = column.width
                            active = model.sortBy == column.field
                            direction = if (model.sortAscending) SortDirection.ASC else SortDirection.DESC
                            onClick = { component.onSortChanged(column.field) }
                        }
                    }
                    // ID isn't a useful sort key — left as a plain label.
                    TableHeaderCell {
                        width = 140.px
                        +"ID"
                    }
                }
            }
            TableBody {
                when {
                    model.loading && model.items.isEmpty() -> TableMessageRow {
                        columns = COLUMN_COUNT
                        SkeletonRows {
                            rows = 10
                            widths = listOf(110.px, 100.px, 260.px, 56.px, 70.px, 70.px, 90.px)
                        }
                    }

                    model.error != null -> TableMessageRow {
                        columns = COLUMN_COUNT
                        div {
                            css { padding = 16.px }
                            ErrorBanner {
                                message = "Error: ${model.error}"
                                onRetry = component::onRefreshClicked
                            }
                        }
                    }

                    model.items.isEmpty() -> TableMessageRow {
                        columns = COLUMN_COUNT
                        EmptyState {
                            title = "No jobs match the current filter"
                            hint = "Clear a state chip or widen the queue / type filter."
                        }
                    }

                    else -> model.items.forEach { row ->
                        JobRow {
                            key = Key(row.id)
                            job = row
                            checked = row.id in model.selectedIds
                            paused = row.payloadType in model.pausedTypes
                            ageAbsolute = model.ageAbsolute
                            onCheckedChange = { checked -> component.onJobChecked(row.id, checked) }
                            onOpen = { component.onJobClicked(row.id) }
                        }
                    }
                }
            }
        }

        // Second pagination bar below the table — the operator can page without scrolling back to
        // the top of a long list.
        PaginationBar {
            page = model.page
            pageSize = model.pageSize
            total = model.total
            loading = model.loading
            onPrev = component::onPrevPageClicked
            onNext = component::onNextPageClicked
            onSizeChange = component::onPageSizeChanged
        }
    }
}

private external interface JobListActionsProps : Props {
    var component: JobListComponent
}

private val JobListActions: FC<JobListActionsProps> = FC { props ->
    val component = props.component
    val model = useValue(component.model)

    SettingsMenu {
        autoRefreshSeconds = model.autoRefreshSeconds
        onAutoRefreshChanged = component::onAutoRefreshChanged
        timeSectionLabel = "Time columns"
        relativeLabel = "Relative (3m ago)"
        timeAbsolute = model.ageAbsolute
        onTimeModeChanged = component::onAgeModeChanged
        stickToTop = model.stickToTop
        onStickToTopChanged = component::onStickToTopChanged
    }
    Button {
        onClick = component::onRefreshClicked
        +"Refresh"
    }
}

// ---- filters ----------------------------------------------------------------------------------

private external interface StateFilterRowProps : Props {
    var selected: Set<JobState>
    var dlqOnly: Boolean
    var onChanged: (Set<JobState>) -> Unit
    var onDlqToggle: (Boolean) -> Unit
}

private val StateFilterRow: FC<StateFilterRowProps> = FC { props ->
    div {
        css {
            flexRow(gap = 8.px)
            flexWrap = FlexWrap.wrap
            padding = Padding(8.px, 16.px)
        }
        span {
            css {
                +SchedulerText.labelMedium
                color = SchedulerColors.onSurfaceVariant
            }
            +"State:"
        }
        JobState.entries.forEach { state ->
            ToggleChip {
                key = Key(state.name)
                selected = state in props.selected
                onToggle = {
                    val next = if (state in props.selected) props.selected - state else props.selected + state
                    props.onChanged(next)
                }
                +state.name
            }
        }
        span {
            css { color = SchedulerColors.outlineVariant }
            +"·"
        }
        ToggleChip {
            selected = props.dlqOnly
            title = "Only jobs whose auto-retry budget is exhausted"
            onToggle = { props.onDlqToggle(!props.dlqOnly) }
            +"Dead-letter only"
        }
    }
}

private external interface QueueHealthRowProps : Props {
    var health: List<QueueHealthDto>
}

/**
 * ELEVATED + OVERLOADED queue badges. NORMAL queues render nothing, so the row disappears
 * entirely when everything is healthy — no empty strip of chrome.
 */
private val QueueHealthRow: FC<QueueHealthRowProps> = FC { props ->
    val visible = props.health.filter { it.status != QueueHealthStatus.NORMAL }
    if (visible.isNotEmpty()) {
        div {
            css {
                flexRow(gap = 6.px)
                flexWrap = FlexWrap.wrap
                padding = Padding(12.px, 16.px)
            }
            visible.forEach { queue ->
                QueueHealthBadge {
                    key = Key(queue.queue)
                    item = queue
                }
            }
        }
    }
}

// ---- bulk actions -----------------------------------------------------------------------------

private external interface BulkActionToolbarProps : Props {
    var selectedCount: Int
    var inFlight: Boolean
    var actionLabel: String?
    var result: BulkActionResponse?
    var onRetry: () -> Unit
    var onCancel: () -> Unit
    var onDelete: () -> Unit
    var onClear: () -> Unit
    var onDismiss: () -> Unit
}

private val BulkActionToolbar: FC<BulkActionToolbarProps> = FC { props ->
    val actionable = !props.inFlight && props.selectedCount > 0

    div {
        css {
            flexColumn(gap = 6.px)
            padding = Padding(8.px, 16.px)
            backgroundColor = SchedulerColors.surfaceVariant
            borderBottom = Border(1.px, LineStyle.solid, SchedulerColors.outlineVariant)
        }

        div {
            css { flexRow(gap = 12.px) }
            span {
                css {
                    +SchedulerText.titleSmall
                    color = SchedulerColors.onSurface
                }
                +if (props.inFlight) "${props.actionLabel ?: "Processing"}…" else "${props.selectedCount} selected"
            }
            Button {
                variant = ButtonVariant.FILLED
                size = ButtonSize.SMALL
                disabled = !actionable
                onClick = props.onRetry
                +"Retry"
            }
            Button {
                size = ButtonSize.SMALL
                disabled = !actionable
                onClick = props.onCancel
                +"Cancel"
            }
            Button {
                variant = ButtonVariant.DANGER
                size = ButtonSize.SMALL
                disabled = !actionable
                onClick = props.onDelete
                +"Delete"
            }
            Button {
                variant = ButtonVariant.TEXT
                size = ButtonSize.SMALL
                disabled = props.inFlight
                onClick = props.onClear
                +"Clear"
            }
        }

        props.result?.let { result ->
            div {
                css { flexRow(gap = 12.px) }
                span {
                    css {
                        +SchedulerText.bodySmall
                        color = SchedulerColors.primary
                    }
                    val breakdown = formatBreakdown(result.byOutcome)
                    +"${props.actionLabel ?: "Bulk"} result: ${result.ok}/${result.total} ok — $breakdown"
                }
                Button {
                    variant = ButtonVariant.TEXT
                    size = ButtonSize.SMALL
                    onClick = props.onDismiss
                    +"Dismiss"
                }
            }
        }
    }
}

private fun formatBreakdown(byOutcome: Map<String, Int>): String =
    if (byOutcome.isEmpty()) {
        "no outcomes"
    } else {
        byOutcome.entries
            .sortedByDescending { it.value }
            .joinToString(", ") { "${it.key}=${it.value}" }
    }

// ---- pagination -------------------------------------------------------------------------------

private external interface PaginationBarProps : Props {
    var page: Int
    var pageSize: Int
    var total: Long
    var loading: Boolean
    var onPrev: () -> Unit
    var onNext: () -> Unit
    var onSizeChange: (Int) -> Unit
}

private val PaginationBar: FC<PaginationBarProps> = FC { props ->
    val totalPages = if (props.total <= 0) 1 else (((props.total - 1) / props.pageSize) + 1).toInt()
    val pageIndex = props.page + 1
    val from = if (props.total == 0L) 0L else props.page.toLong() * props.pageSize + 1
    val to = if (props.total == 0L) 0L else minOf((props.page + 1).toLong() * props.pageSize, props.total)

    div {
        css {
            flexRow(gap = 16.px, justify = JustifyContent.spaceBetween)
            flexWrap = FlexWrap.wrap
            padding = Padding(8.px, 16.px)
            borderBottom = Border(1.px, LineStyle.solid, SchedulerColors.outlineVariant)
        }

        div {
            css { flexRow(gap = 16.px) }
            IconButton {
                title = "Previous page"
                disabled = props.loading || props.page == 0
                onClick = props.onPrev
                size = 30.px
                ChevronLeftIcon { size = 14.px }
            }
            span {
                css {
                    +SchedulerText.labelLarge
                    color = SchedulerColors.onSurface
                }
                +"Page $pageIndex of $totalPages"
            }
            IconButton {
                title = "Next page"
                disabled = props.loading || pageIndex >= totalPages
                onClick = props.onNext
                size = 30.px
                ChevronRightIcon { size = 14.px }
            }
        }

        div {
            css { flexRow(gap = 12.px) }
            span {
                css {
                    +SchedulerText.mono
                    color = SchedulerColors.onSurfaceVariant
                }
                +if (props.total == 0L) "0 jobs" else "$from–$to of ${props.total}"
            }
            span {
                css {
                    +SchedulerText.labelMedium
                    color = SchedulerColors.onSurfaceVariant
                }
                +"Rows"
            }
            div {
                css { flexRow(gap = 4.px) }
                PAGE_SIZES.forEach { size ->
                    ToggleChip {
                        key = Key(size)
                        selected = props.pageSize == size
                        onToggle = { if (props.pageSize != size) props.onSizeChange(size) }
                        +size.toString()
                    }
                }
            }
        }
    }
}

private val PAGE_SIZES = listOf(50, 100, 200)

// ---- rows -------------------------------------------------------------------------------------

private external interface JobRowProps : Props {
    var job: JobView
    var checked: Boolean
    var paused: Boolean
    var ageAbsolute: Boolean
    var onCheckedChange: (Boolean) -> Unit
    var onOpen: () -> Unit
}

private val JobRow: FC<JobRowProps> = FC { props ->
    val job = props.job

    TableRow {
        onClick = props.onOpen
        selected = props.checked

        TableCell {
            width = 44.px
            // The row navigates on click; the checkbox must not also open the job.
            div {
                onClick = { event -> event.stopPropagation() }
                Checkbox {
                    checked = props.checked
                    onCheckedChange = props.onCheckedChange
                }
            }
        }

        TableCell {
            div {
                css { flexColumn(gap = 6.px) }
                StateChip { state = job.state }
                // Mini progress bar under the state chip, for PROCESSING rows. Counting jobs
                // (succeeded/failed/total reported) get the green/red split like the detail
                // screen; plain updateProgress jobs get a single cobalt bar.
                if (job.state == JobState.PROCESSING) {
                    val succeeded = job.progressSucceeded
                    val failed = job.progressFailed
                    val total = job.progressTotal
                    if (succeeded != null && failed != null && total != null && total > 0L) {
                        MiniBar {
                            segments = listOf(
                                succeeded.toDouble() / total to SchedulerColors.success,
                                failed.toDouble() / total to SchedulerColors.error,
                            )
                        }
                    } else {
                        job.progress?.let { fraction ->
                            MiniBar {
                                segments = listOf(fraction.toDouble() to SchedulerColors.primary)
                            }
                        }
                    }
                }
            }
        }

        TableCell { +job.queue }

        TableCell {
            div {
                css { flexRow(gap = 8.px) }
                div {
                    css {
                        flexGrow = number(1.0)
                        minWidth = 0.px
                    }
                    // Simple name shown; full FQN on hover and on copy.
                    CopyableText {
                        text = job.payloadType.substringAfterLast('.')
                        copyValue = job.payloadType
                        tooltip = job.payloadType
                    }
                }
                if (props.paused) PausedBadge()
            }
        }

        TableCell {
            nowrap = true
            +"${job.attempts}/${job.maxAttempts}"
        }

        TableCell {
            nowrap = true
            // Started — when a worker first picked the job up. "—" (muted) until it runs.
            span {
                css {
                    color = if (job.startedAt != null) SchedulerColors.onSurface else SchedulerColors.onSurfaceVariant
                }
                +(job.startedAt?.let { if (props.ageAbsolute) formatDateTime(it) else timeAgo(it) } ?: "—")
            }
        }

        TableCell {
            nowrap = true
            +if (props.ageAbsolute) formatDateTime(job.updatedAt) else timeAgo(job.updatedAt)
        }

        TableCell {
            nowrap = true
            span {
                css {
                    +SchedulerText.mono
                    color = SchedulerColors.onSurfaceVariant
                    ellipsis()
                }
                +job.id.take(8)
            }
        }
    }
}

private external interface MiniBarProps : Props {
    /** (fraction of the total, fill colour) pairs, drawn left to right over a neutral track. */
    var segments: List<Pair<Double, Color>>
}

/**
 * Thin progress strip under a PROCESSING row's state chip — the remaining gap is work not yet
 * processed. Matches the JobDetail counting bar so the list and the card read the same.
 */
private val MiniBar: FC<MiniBarProps> = FC { props ->
    div {
        css {
            flexRow()
            width = 100.pct
            height = 4.px
            borderRadius = SchedulerRadius.extraSmall
            overflow = Overflow.hidden
            backgroundColor = SchedulerColors.surfaceContainerHigh
        }
        var consumed = 0.0
        props.segments.forEach { (fraction, fill) ->
            val clamped = fraction.coerceIn(0.0, (1.0 - consumed).coerceAtLeast(0.0))
            if (clamped > 0.0) {
                div {
                    css {
                        width = (clamped * 100).pct
                        height = 100.pct
                        backgroundColor = fill
                    }
                }
            }
            consumed += clamped
        }
    }
}

// Checkbox column + the seven data columns.
private const val COLUMN_COUNT = 9

private class SortableColumn(val label: String, val width: Length?, val field: JobSortField)

private val SORTABLE_COLUMNS: List<SortableColumn> = listOf(
    SortableColumn("State", 180.px, JobSortField.STATE),
    SortableColumn("Queue", 130.px, JobSortField.QUEUE),
    // Name takes the flexible width — it holds the longest values.
    SortableColumn("Name", null, JobSortField.TYPE),
    SortableColumn("Attempts", 90.px, JobSortField.ATTEMPTS),
    SortableColumn("Started", 170.px, JobSortField.STARTED),
    SortableColumn("Age", 170.px, JobSortField.UPDATED),
)
