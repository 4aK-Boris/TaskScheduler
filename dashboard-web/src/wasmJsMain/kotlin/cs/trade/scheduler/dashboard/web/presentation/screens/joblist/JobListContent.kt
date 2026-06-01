package cs.trade.scheduler.dashboard.web.presentation.screens.joblist

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TriStateCheckbox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.arkivanov.decompose.extensions.compose.subscribeAsState
import cs.trade.scheduler.core.frontend.theme.schedulerColors
import cs.trade.scheduler.dashboard.web.presentation.components.PausedBadge
import cs.trade.scheduler.dashboard.web.presentation.components.QueueHealthBadge
import cs.trade.scheduler.dashboard.web.presentation.components.SortDirection
import cs.trade.scheduler.dashboard.web.presentation.components.SortableHeaderCell
import cs.trade.scheduler.dashboard.web.presentation.components.StateChip
import cs.trade.scheduler.dashboard.web.presentation.components.timeAgo
import cs.trade.scheduler.dashboard.web.presentation.components.DashboardPanel
import cs.trade.scheduler.dashboard.web.presentation.components.PageHeader
import cs.trade.scheduler.dashboard.web.presentation.components.SettingsMenu
import cs.trade.scheduler.dashboard.web.presentation.components.SkeletonBar
import cs.trade.scheduler.dashboard.web.presentation.components.CopyableText
import cs.trade.scheduler.shared.JobSortField
import cs.trade.scheduler.shared.JobState
import cs.trade.scheduler.shared.dto.BulkActionResponse
import cs.trade.scheduler.shared.dto.JobView
import cs.trade.scheduler.shared.dto.QueueHealthDto
import cs.trade.scheduler.shared.dto.QueueHealthStatus
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import cs.trade.scheduler.dashboard.web.presentation.components.formatDateTime

// Fixed columns (checkbox 44 + state 168 + queue 120 + attempts 80 + started 160 + age 160 + id 130
// = 862) plus a floor for the flexible Name column. Started/Age are 160 to fit the full absolute
// "DD.MM.YYYY HH:mm:ss". Above this the table fills the panel (Name absorbs the slack); below it pans.
private val TABLE_MIN_WIDTH = 1172.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
public fun JobListContent(component: JobListComponent) {
    val state by component.model.subscribeAsState()
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        PageHeader(title = "Jobs", count = state.total) {
            SettingsMenu(
                autoRefreshSeconds = state.autoRefreshSeconds,
                onAutoRefreshChanged = component::onAutoRefreshChanged,
                timeSectionLabel = "Time columns",
                relativeLabel = "Relative (3m ago)",
                timeAbsolute = state.ageAbsolute,
                onTimeModeChanged = component::onAgeModeChanged,
            )
            OutlinedButton(onClick = component::onRefreshClicked, shape = MaterialTheme.shapes.small) {
                Text("Refresh")
            }
        }
        DashboardPanel {
            Column(modifier = Modifier.fillMaxSize()) {
                StateFilterRow(
                    selected = state.stateFilter,
                    dlqOnly = state.dlqOnly,
                    onChanged = component::onStateFilterChanged,
                    onDlqToggle = component::onDlqOnlyToggled,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                )
                ExtraFiltersRow(
                    queue = state.queueFilter,
                    payloadType = state.payloadTypeFilter,
                    knownQueues = state.queueHealth.map { it.queue }.distinct().sorted(),
                    knownTypes = state.knownTypes,
                    onQueueChange = component::onQueueFilterChanged,
                    onPayloadTypeChange = component::onPayloadTypeFilterChanged,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                )
                QueueHealthRow(
                    health = state.queueHealth,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                if (state.selectedIds.isNotEmpty() || state.bulkResult != null || state.bulkInFlight) {
                    BulkActionToolbar(
                        selectedCount = state.selectedIds.size,
                        inFlight = state.bulkInFlight,
                        actionLabel = state.bulkActionLabel,
                        result = state.bulkResult,
                        onRetry = component::onBulkRetryClicked,
                        onCancel = component::onBulkCancelClicked,
                        onDelete = component::onBulkDeleteClicked,
                        onClear = component::onClearSelection,
                        onDismiss = component::onDismissBulkResult,
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                }
                PaginationBar(
                    page = state.page,
                    pageSize = state.pageSize,
                    total = state.total,
                    loading = state.loading,
                    onPrev = component::onPrevPageClicked,
                    onNext = component::onNextPageClicked,
                    onSizeChange = component::onPageSizeChanged,
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                // Table takes the remaining height (weight) so a second pagination bar can sit
                // below it. Fills the panel above TABLE_MIN_WIDTH (Name absorbs the slack); below
                // it the operator pans horizontally.
                BoxWithConstraints(modifier = Modifier.fillMaxWidth().weight(1f)) {
                    val tableWidth = maxOf(maxWidth, TABLE_MIN_WIDTH)
                    val tableScroll = rememberScrollState()
                    Box(modifier = Modifier.fillMaxSize().horizontalScroll(tableScroll)) {
                        Column(modifier = Modifier.width(tableWidth).fillMaxHeight()) {
                            JobListHeader(
                                allVisibleSelected = state.items.isNotEmpty() &&
                                    state.items.all { it.id in state.selectedIds },
                                anySelected = state.selectedIds.isNotEmpty(),
                                onToggleAll = component::onSelectAllVisibleClicked,
                                sortBy = state.sortBy,
                                sortAscending = state.sortAscending,
                                onSort = component::onSortChanged,
                            )
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                            Box(modifier = Modifier.fillMaxWidth().weight(1f)) {
                                when {
                                    state.loading && state.items.isEmpty() ->
                                        JobListSkeleton(modifier = Modifier.align(Alignment.TopStart))
                                    state.error != null -> Text(
                                        text = "Error: ${state.error}",
                                        color = MaterialTheme.colorScheme.error,
                                        style = MaterialTheme.typography.bodyMedium,
                                        modifier = Modifier.align(Alignment.Center).padding(24.dp),
                                    )
                                    state.items.isEmpty() -> Text(
                                        text = "No jobs match the current filter",
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        style = MaterialTheme.typography.bodyMedium,
                                        modifier = Modifier.align(Alignment.Center).padding(24.dp),
                                    )
                                    else -> LazyColumn(modifier = Modifier.fillMaxSize()) {
                                        items(state.items, key = { it.id }) { row ->
                                            JobRow(
                                                job = row,
                                                checked = row.id in state.selectedIds,
                                                paused = row.payloadType in state.pausedTypes,
                                                ageAbsolute = state.ageAbsolute,
                                                onCheckedChange = { component.onJobChecked(row.id, it) },
                                                onClick = { component.onJobClicked(row.id) },
                                            )
                                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                // Second pagination bar below the table — operator can page without scrolling
                // back to the top of a long list.
                PaginationBar(
                    page = state.page,
                    pageSize = state.pageSize,
                    total = state.total,
                    loading = state.loading,
                    onPrev = component::onPrevPageClicked,
                    onNext = component::onNextPageClicked,
                    onSizeChange = component::onPageSizeChanged,
                )
            }
        }
    }
}

// Placeholder rows while the first page loads — calmer than a bare spinner.
@Composable
private fun JobListSkeleton(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxWidth().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        repeat(10) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SkeletonBar(110.dp)
                SkeletonBar(100.dp)
                SkeletonBar(260.dp)
                SkeletonBar(56.dp)
                SkeletonBar(70.dp)
                SkeletonBar(70.dp)
                SkeletonBar(90.dp)
            }
        }
    }
}

@Composable
private fun PaginationBar(
    page: Int,
    pageSize: Int,
    total: Long,
    loading: Boolean,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    onSizeChange: (Int) -> Unit,
) {
    val totalPages = if (total <= 0) 1 else (((total - 1) / pageSize) + 1).toInt()
    val pageIndex = page + 1
    val from = if (total == 0L) 0L else page.toLong() * pageSize + 1
    val to = if (total == 0L) 0L else minOf((page + 1).toLong() * pageSize, total)
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            NavBtn(left = true, enabled = !loading && page > 0, onClick = onPrev)
            Text(
                text = "Page $pageIndex of $totalPages",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            NavBtn(left = false, enabled = !loading && pageIndex < totalPages, onClick = onNext)
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                text = if (total == 0L) "0 jobs" else "$from–$to of $total",
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text("Rows", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                listOf(50, 100, 200).forEach { size ->
                    FilterChip(
                        selected = pageSize == size,
                        onClick = { if (pageSize != size) onSizeChange(size) },
                        label = { Text(size.toString()) },
                        colors = FilterChipDefaults.filterChipColors(),
                    )
                }
            }
        }
    }
}

// Square outlined chevron button for pagination — dimmed + non-clickable when disabled.
@Composable
private fun NavBtn(left: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outlineVariant
    Box(
        modifier = Modifier
            .size(width = 36.dp, height = 30.dp)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, MaterialTheme.shapes.small)
            .then(if (enabled) Modifier.clickable(onClick = onClick) else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(modifier = Modifier.size(9.dp)) {
            val w = size.width
            val h = size.height
            val sw = w * 0.18f
            if (left) {
                drawLine(color, Offset(w * 0.62f, h * 0.15f), Offset(w * 0.30f, h * 0.5f), sw, cap = StrokeCap.Round)
                drawLine(color, Offset(w * 0.30f, h * 0.5f), Offset(w * 0.62f, h * 0.85f), sw, cap = StrokeCap.Round)
            } else {
                drawLine(color, Offset(w * 0.38f, h * 0.15f), Offset(w * 0.70f, h * 0.5f), sw, cap = StrokeCap.Round)
                drawLine(color, Offset(w * 0.70f, h * 0.5f), Offset(w * 0.38f, h * 0.85f), sw, cap = StrokeCap.Round)
            }
        }
    }
}


@Composable
private fun ExtraFiltersRow(
    queue: String,
    payloadType: String,
    knownQueues: List<String>,
    knownTypes: List<String>,
    onQueueChange: (String) -> Unit,
    onPayloadTypeChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AutocompleteFilter("Queue", queue, knownQueues, onQueueChange, width = 220.dp)
        AutocompleteFilter("Task Type", payloadType, knownTypes, onPayloadTypeChange, width = 320.dp)
    }
}

// Free-text field + dropdown of known values, filtered by what's typed. Shared by the Queue
// and Payload-type filters: pick an existing value, or type a rare one by hand.
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AutocompleteFilter(
    label: String,
    value: String,
    suggestions: List<String>,
    onValueChange: (String) -> Unit,
    width: Dp,
) {
    val matches = remember(value, suggestions) {
        val needle = value.trim()
        suggestions.filter { it.contains(needle, ignoreCase = true) }.take(30)
    }
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(
        expanded = expanded && matches.isNotEmpty(),
        onExpandedChange = { expanded = it },
        modifier = Modifier.width(width),
    ) {
        OutlinedTextField(
            value = value,
            onValueChange = {
                onValueChange(it)
                expanded = true
            },
            singleLine = true,
            label = { Text(label) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            textStyle = MaterialTheme.typography.bodySmall,
            modifier = Modifier
                .fillMaxWidth()
                .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryEditable, enabled = true),
        )
        ExposedDropdownMenu(
            expanded = expanded && matches.isNotEmpty(),
            onDismissRequest = { expanded = false },
        ) {
            matches.forEach { match ->
                DropdownMenuItem(
                    text = { Text(match, style = MaterialTheme.typography.bodySmall) },
                    onClick = {
                        onValueChange(match)
                        expanded = false
                    },
                )
            }
        }
    }
}

@Composable
private fun BulkActionToolbar(
    selectedCount: Int,
    inFlight: Boolean,
    actionLabel: String?,
    result: BulkActionResponse?,
    onRetry: () -> Unit,
    onCancel: () -> Unit,
    onDelete: () -> Unit,
    onClear: () -> Unit,
    onDismiss: () -> Unit,
) {
    Surface(color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    text = if (inFlight) "${actionLabel ?: "Processing"}…" else "$selectedCount selected",
                    style = MaterialTheme.typography.titleSmall,
                )
                Spacer(Modifier.width(1.dp))
                Button(onClick = onRetry, enabled = !inFlight && selectedCount > 0) { Text("Retry") }
                Button(
                    onClick = onCancel,
                    enabled = !inFlight && selectedCount > 0,
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.tertiary),
                ) { Text("Cancel") }
                Button(
                    onClick = onDelete,
                    enabled = !inFlight && selectedCount > 0,
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                ) { Text("Delete") }
                OutlinedButton(onClick = onClear, enabled = !inFlight) { Text("Clear") }
            }
            result?.let {
                Spacer(Modifier.padding(top = 4.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(
                        text = "${actionLabel ?: "Bulk"} result: ${it.ok}/${it.total} ok — ${formatBreakdown(it.byOutcome)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    OutlinedButton(onClick = onDismiss) { Text("Dismiss") }
                }
            }
        }
    }
}

private fun formatBreakdown(byOutcome: Map<String, Int>): String =
    if (byOutcome.isEmpty()) "no outcomes"
    else byOutcome.entries
        .sortedByDescending { it.value }
        .joinToString(", ") { "${it.key}=${it.value}" }

// Renders ELEVATED + OVERLOADED queue badges. NORMAL items short-circuit inside the
// badge composable, so the row collapses to zero height when everything is healthy
// (FlowRow with no children renders nothing visible).
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun QueueHealthRow(health: List<QueueHealthDto>, modifier: Modifier = Modifier) {
    val visible = health.filter { it.status != QueueHealthStatus.NORMAL }
    if (visible.isEmpty()) return
    FlowRow(
        modifier = modifier.padding(vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        visible.forEach { item -> QueueHealthBadge(item) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun StateFilterRow(
    selected: Set<JobState>,
    dlqOnly: Boolean,
    onChanged: (Set<JobState>) -> Unit,
    onDlqToggle: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("State:", style = MaterialTheme.typography.labelMedium)
        JobState.entries.forEach { st ->
            FilterChip(
                selected = st in selected,
                onClick = {
                    val next = if (st in selected) selected - st else selected + st
                    onChanged(next)
                },
                label = { Text(st.name) },
                colors = FilterChipDefaults.filterChipColors(),
            )
        }
        Text("·", style = MaterialTheme.typography.labelMedium)
        FilterChip(
            selected = dlqOnly,
            onClick = { onDlqToggle(!dlqOnly) },
            label = { Text("Dead-letter only") },
            colors = FilterChipDefaults.filterChipColors(),
        )
    }
}

@Composable
private fun JobListHeader(
    allVisibleSelected: Boolean,
    anySelected: Boolean,
    onToggleAll: (Boolean) -> Unit,
    sortBy: JobSortField?,
    sortAscending: Boolean,
    onSort: (JobSortField) -> Unit,
) {
    val dir = if (sortAscending) SortDirection.ASC else SortDirection.DESC
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(modifier = Modifier.width(44.dp)) {
            TriStateCheckbox(
                state = when {
                    allVisibleSelected -> ToggleableState.On
                    anySelected -> ToggleableState.Indeterminate
                    else -> ToggleableState.Off
                },
                onClick = { onToggleAll(!allVisibleSelected) },
            )
        }
        SortHead("State", Modifier.width(168.dp), JobSortField.STATE, sortBy, dir, onSort)
        SortHead("Queue", Modifier.width(120.dp), JobSortField.QUEUE, sortBy, dir, onSort)
        SortHead("Name", Modifier.weight(1f), JobSortField.TYPE, sortBy, dir, onSort)
        SortHead("Attempts", Modifier.width(80.dp), JobSortField.ATTEMPTS, sortBy, dir, onSort)
        SortHead("Started", Modifier.width(160.dp), JobSortField.STARTED, sortBy, dir, onSort)
        SortHead("Age", Modifier.width(160.dp), JobSortField.UPDATED, sortBy, dir, onSort)
        // ID isn't a useful sort key — left as a plain label.
        HeaderCell("ID", Modifier.width(130.dp))
    }
}

@Composable
private fun SortHead(
    label: String,
    modifier: Modifier,
    field: JobSortField,
    sortBy: JobSortField?,
    direction: SortDirection,
    onSort: (JobSortField) -> Unit,
) {
    SortableHeaderCell(
        label = label,
        modifier = modifier,
        active = sortBy == field,
        direction = direction,
        onClick = { onSort(field) },
    )
}

// Uppercase, tracked column label — matches the top-nav treatment.
@Composable
private fun HeaderCell(label: String, modifier: Modifier) {
    Text(
        text = label.uppercase(),
        style = MaterialTheme.typography.labelSmall.copy(
            fontWeight = FontWeight.Medium,
            letterSpacing = 0.6.sp,
        ),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier,
    )
}

// Thin two-colour progress bar: green succeeded + red failed over a neutral track (the remaining
// gap = not-yet-processed). Matches the JobDetail counting bar so the list and card read the same.
@Composable
private fun CountingMiniBar(succeeded: Long, failed: Long, total: Long, modifier: Modifier = Modifier) {
    val succeededFrac = (succeeded.toFloat() / total).coerceIn(0f, 1f)
    val failedFrac = (failed.toFloat() / total).coerceIn(0f, 1f - succeededFrac)
    val remaining = (1f - succeededFrac - failedFrac).coerceIn(0f, 1f)
    Box(
        modifier = modifier
            .height(4.dp)
            .clip(RoundedCornerShape(2.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh),
    ) {
        Row(Modifier.fillMaxSize()) {
            if (succeededFrac > 0f) {
                Box(Modifier.fillMaxHeight().weight(succeededFrac).background(MaterialTheme.schedulerColors.success))
            }
            if (failedFrac > 0f) {
                Box(Modifier.fillMaxHeight().weight(failedFrac).background(MaterialTheme.colorScheme.error))
            }
            if (remaining > 0f) Box(Modifier.fillMaxHeight().weight(remaining))
        }
    }
}

@Composable
private fun JobRow(
    job: JobView,
    checked: Boolean,
    paused: Boolean,
    ageAbsolute: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    onClick: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .hoverable(interaction)
            .background(if (hovered) MaterialTheme.colorScheme.surfaceContainerLow else Color.Transparent)
            .heightIn(min = 44.dp)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(modifier = Modifier.width(44.dp)) {
            Checkbox(checked = checked, onCheckedChange = onCheckedChange)
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(modifier = Modifier.width(168.dp)) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    StateChip(job.state)
                    // Mini progress bar under the state chip, for PROCESSING rows. Counting jobs
                    // (succeeded/failed/total reported) get the green/red split like the detail
                    // screen; plain updateProgress jobs get a single bar. end padding clears Queue.
                    if (job.state == JobState.PROCESSING) {
                        val s = job.progressSucceeded
                        val f = job.progressFailed
                        val t = job.progressTotal
                        if (s != null && f != null && t != null && t > 0L) {
                            CountingMiniBar(s, f, t, modifier = Modifier.fillMaxWidth().padding(end = 16.dp))
                        } else {
                            job.progress?.let { p ->
                                LinearProgressIndicator(
                                    progress = { p.coerceIn(0f, 1f) },
                                    modifier = Modifier.fillMaxWidth().padding(end = 16.dp).heightIn(min = 3.dp, max = 4.dp),
                                )
                            }
                        }
                    }
                }
            }
            Text(
                job.queue,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.width(120.dp),
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                // end padding keeps a clear gap to the Attempts column (esp. the PAUSED pill).
                modifier = Modifier.weight(1f).padding(end = 16.dp),
            ) {
                // Weight on a plain Box (reliable in Row layout); the text fills it and ellipsises.
                // Keeps the PAUSED pill its own slot instead of squeezing it to one char per line.
                Box(modifier = Modifier.weight(1f)) {
                    // Simple name shown; full FQN on hover and on copy.
                    CopyableText(
                        text = job.payloadType.substringAfterLast('.'),
                        copyValue = job.payloadType,
                        tooltip = job.payloadType,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                if (paused) PausedBadge()
            }
            Text(
                text = "${job.attempts}/${job.maxAttempts}",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.width(80.dp),
            )
            // Started — when a worker first picked the job up. "—" (muted) until it runs.
            Text(
                text = job.startedAt?.let { if (ageAbsolute) formatDateTime(it) else timeAgo(it) } ?: "—",
                style = MaterialTheme.typography.bodyMedium,
                color = if (job.startedAt != null) MaterialTheme.colorScheme.onSurface
                else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.width(160.dp),
            )
            Text(
                text = if (ageAbsolute) formatDateTime(job.updatedAt) else timeAgo(job.updatedAt),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.width(160.dp),
            )
            Text(
                text = job.id.take(8),
                style = MaterialTheme.typography.bodySmall.copy(
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontFamily = FontFamily.Monospace,
                ),
                modifier = Modifier.width(130.dp),
            )
        }
    }
}
