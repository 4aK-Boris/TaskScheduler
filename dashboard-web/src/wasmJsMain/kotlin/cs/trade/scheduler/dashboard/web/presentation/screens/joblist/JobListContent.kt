package cs.trade.scheduler.dashboard.web.presentation.screens.joblist

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TriStateCheckbox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.arkivanov.decompose.extensions.compose.subscribeAsState
import cs.trade.scheduler.dashboard.web.presentation.components.PausedBadge
import cs.trade.scheduler.dashboard.web.presentation.components.StateChip
import cs.trade.scheduler.dashboard.web.presentation.components.timeAgo
import cs.trade.scheduler.shared.JobState
import cs.trade.scheduler.shared.dto.BulkActionResponse
import cs.trade.scheduler.shared.dto.JobView

// Sum of column widths (checkbox 44 + state 140 + queue 120 + payload 320 + attempts 80
// + age 96 + id 130). We force this min width so the row keeps its grid even on a
// narrow viewport — operator scrolls horizontally instead of seeing collapsed columns.
private val TABLE_MIN_WIDTH = 950.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
public fun JobListContent(component: JobListComponent) {
    val state by component.model.subscribeAsState()
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Jobs (${state.total})") },
                actions = {
                    OutlinedButton(onClick = component::onRefreshClicked) { Text("Refresh") }
                    Spacer(Modifier.width(8.dp))
                },
            )
        },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
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
                knownTypes = state.knownTypes,
                onQueueChange = component::onQueueFilterChanged,
                onPayloadTypeChange = component::onPayloadTypeFilterChanged,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
            )
            HorizontalDivider()
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
                HorizontalDivider()
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
            HorizontalDivider()
            // Table — wraps in horizontalScroll. requiredWidth keeps the grid sane
            // when viewport < TABLE_MIN_WIDTH; operator pans horizontally on phones.
            val tableScroll = rememberScrollState()
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .horizontalScroll(tableScroll),
            ) {
                JobListHeader(
                    allVisibleSelected = state.items.isNotEmpty() &&
                        state.items.all { it.id in state.selectedIds },
                    anySelected = state.selectedIds.isNotEmpty(),
                    onToggleAll = component::onSelectAllVisibleClicked,
                )
                HorizontalDivider(modifier = Modifier.requiredWidth(TABLE_MIN_WIDTH))
                Box(modifier = Modifier.fillMaxSize().requiredWidth(TABLE_MIN_WIDTH)) {
                    when {
                        state.loading && state.items.isEmpty() -> CircularProgressIndicator(
                            modifier = Modifier.align(Alignment.Center),
                        )
                        state.error != null -> Text(
                            text = "Error: ${state.error}",
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.align(Alignment.Center).padding(16.dp),
                        )
                        state.items.isEmpty() -> Text(
                            text = "No jobs match the current filter",
                            modifier = Modifier.align(Alignment.Center).padding(16.dp),
                        )
                        else -> LazyColumn(modifier = Modifier.fillMaxSize()) {
                            items(state.items, key = { it.id }) { row ->
                                JobRow(
                                    job = row,
                                    checked = row.id in state.selectedIds,
                                    paused = row.payloadType in state.pausedTypes,
                                    onCheckedChange = { component.onJobChecked(row.id, it) },
                                    onClick = { component.onJobClicked(row.id) },
                                )
                                HorizontalDivider()
                            }
                        }
                    }
                }
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
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        OutlinedButton(onClick = onPrev, enabled = !loading && page > 0) { Text("Prev") }
        Text(
            text = "Page $pageIndex / $totalPages — $total total",
            style = MaterialTheme.typography.bodySmall,
        )
        OutlinedButton(onClick = onNext, enabled = !loading && pageIndex < totalPages) { Text("Next") }
        Spacer(Modifier.width(16.dp))
        Text("Size:", style = MaterialTheme.typography.labelMedium)
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ExtraFiltersRow(
    queue: String,
    payloadType: String,
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
        OutlinedTextField(
            value = queue,
            onValueChange = onQueueChange,
            singleLine = true,
            label = { Text("Queue") },
            textStyle = MaterialTheme.typography.bodySmall,
            modifier = Modifier.width(180.dp),
        )
        // Payload-type dropdown driven by knownTypes — same pattern as TypesContent.
        // Free-text fallback is still supported (operator can paste a rare FQN).
        val suggestions = remember(payloadType, knownTypes) {
            val needle = payloadType.trim()
            knownTypes.filter { it.contains(needle, ignoreCase = true) }.take(30)
        }
        var expanded by remember { mutableStateOf(false) }
        ExposedDropdownMenuBox(
            expanded = expanded && suggestions.isNotEmpty(),
            onExpandedChange = { expanded = it },
            modifier = Modifier.width(320.dp),
        ) {
            OutlinedTextField(
                value = payloadType,
                onValueChange = {
                    onPayloadTypeChange(it)
                    expanded = true
                },
                singleLine = true,
                label = { Text("Payload type") },
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                textStyle = MaterialTheme.typography.bodySmall,
                modifier = Modifier
                    .fillMaxWidth()
                    .menuAnchor(MenuAnchorType.PrimaryEditable, enabled = true),
            )
            ExposedDropdownMenu(
                expanded = expanded && suggestions.isNotEmpty(),
                onDismissRequest = { expanded = false },
            ) {
                suggestions.forEach { suggestion ->
                    DropdownMenuItem(
                        text = { Text(suggestion, style = MaterialTheme.typography.bodySmall) },
                        onClick = {
                            onPayloadTypeChange(suggestion)
                            expanded = false
                        },
                    )
                }
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
) {
    Row(
        modifier = Modifier
            .requiredWidth(TABLE_MIN_WIDTH)
            .padding(horizontal = 16.dp, vertical = 8.dp),
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
        Text("State", style = headerStyle(), modifier = Modifier.width(140.dp))
        Text("Queue", style = headerStyle(), modifier = Modifier.width(120.dp))
        Text("Payload", style = headerStyle(), modifier = Modifier.width(320.dp))
        Text("Attempts", style = headerStyle(), modifier = Modifier.width(80.dp))
        Text("Age", style = headerStyle(), modifier = Modifier.width(96.dp))
        Text("ID", style = headerStyle())
    }
}

@Composable
private fun headerStyle() = MaterialTheme.typography.labelSmall.copy(
    color = MaterialTheme.colorScheme.onSurfaceVariant,
)

@Composable
private fun JobRow(
    job: JobView,
    checked: Boolean,
    paused: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .requiredWidth(TABLE_MIN_WIDTH)
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
            Box(modifier = Modifier.width(140.dp)) {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    StateChip(job.state)
                    // Mini progress bar under the state chip — shows only for PROCESSING
                    // rows that have reported. Stays under the chip's fixed 140.dp cell
                    // so column alignment doesn't shift when bars appear/disappear.
                    job.progress?.takeIf { job.state == JobState.PROCESSING }?.let { p ->
                        LinearProgressIndicator(
                            progress = { p.coerceIn(0f, 1f) },
                            modifier = Modifier.fillMaxWidth().heightIn(min = 3.dp, max = 4.dp),
                        )
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
                modifier = Modifier.width(320.dp),
            ) {
                Text(
                    text = job.payloadType.substringAfterLast('.'),
                    style = MaterialTheme.typography.bodyMedium,
                )
                if (paused) PausedBadge()
            }
            Text(
                text = "${job.attempts}/${job.maxAttempts}",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.width(80.dp),
            )
            Text(
                text = timeAgo(job.updatedAt),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.width(96.dp),
            )
            Text(
                text = job.id.take(8),
                style = MaterialTheme.typography.bodySmall.copy(
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontFamily = FontFamily.Monospace,
                ),
            )
        }
    }
}
