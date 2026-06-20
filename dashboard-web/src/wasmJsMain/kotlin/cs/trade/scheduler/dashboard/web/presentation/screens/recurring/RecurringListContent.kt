package cs.trade.scheduler.dashboard.web.presentation.screens.recurring

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.arkivanov.decompose.extensions.compose.subscribeAsState
import cs.trade.scheduler.dashboard.web.presentation.components.DashboardPanel
import cs.trade.scheduler.dashboard.web.presentation.components.PageHeader
import cs.trade.scheduler.dashboard.web.presentation.components.SettingsMenu
import cs.trade.scheduler.dashboard.web.presentation.components.SkeletonBar
import cs.trade.scheduler.dashboard.web.presentation.components.SortDirection
import cs.trade.scheduler.dashboard.web.presentation.components.SortableHeaderCell
import cs.trade.scheduler.dashboard.web.presentation.components.CopyableText
import cs.trade.scheduler.dashboard.web.presentation.components.formatDateTime
import cs.trade.scheduler.dashboard.web.presentation.components.timeAgo
import cs.trade.scheduler.shared.dto.RecurringJobDto
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Instant

// Fixed columns (700 + 96 Actions) + floors for the two flexible columns (ID weight 2, Payload
// weight 1). Next/Last are 160 to fit the full absolute "DD.MM.YYYY HH:mm:ss". ID carries the long
// namespaced keys, so it gets the bigger share; above this width the table fills the panel, below pans.
private val TABLE_MIN_WIDTH = 1320.dp

@Composable
public fun RecurringListContent(component: RecurringListComponent) {
    val state by component.model.subscribeAsState()
    // Client-side sort over the full list. Default: soonest next-run first. Three clicks on a
    // column cycle sort → flip → back to the default.
    var sortKey by remember { mutableStateOf(RC_DEFAULT_SORT) }
    var sortAsc by remember { mutableStateOf(rcDefaultAsc(RC_DEFAULT_SORT)) }
    // Client-side search over the definition name (the ID column — the key passed to
    // `recurring(...)`) and its payload type. Held locally like the sort state: a pure view
    // concern, no server round-trip, no Model/UseCase plumbing.
    var search by remember { mutableStateOf("") }
    val onSort: (RcSort) -> Unit = { key ->
        when {
            key != sortKey -> { sortKey = key; sortAsc = rcDefaultAsc(key) }
            sortAsc == rcDefaultAsc(key) -> sortAsc = !sortAsc
            else -> { sortKey = RC_DEFAULT_SORT; sortAsc = rcDefaultAsc(RC_DEFAULT_SORT) }
        }
    }
    val needle = search.trim()
    val visibleItems = remember(state.items, sortKey, sortAsc, needle) {
        val filtered = if (needle.isEmpty()) state.items
            else state.items.filter {
                // contains() over the full payload type also matches the short name shown in
                // the Payload column (it's a suffix of the fully-qualified type).
                it.id.contains(needle, ignoreCase = true) ||
                    it.payloadType.contains(needle, ignoreCase = true)
            }
        sortRecurring(filtered, sortKey, sortAsc)
    }
    Column(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        // Count reflects what's visible — handy as a live "N matches" while filtering.
        PageHeader(title = "Recurring", count = visibleItems.size.toLong()) {
            SettingsMenu(
                autoRefreshSeconds = state.autoRefreshSeconds,
                onAutoRefreshChanged = component::onAutoRefreshChanged,
                timeSectionLabel = "Next / Last columns",
                relativeLabel = "Relative (2h ago)",
                timeAbsolute = state.ageAbsolute,
                onTimeModeChanged = component::onAgeModeChanged,
            )
            OutlinedButton(onClick = component::onBackClicked, shape = MaterialTheme.shapes.small) { Text("Back") }
            OutlinedButton(onClick = component::onRefreshClicked, shape = MaterialTheme.shapes.small) { Text("Refresh") }
        }
        RecurringSearchBar(value = search, onValueChange = { search = it })
        DashboardPanel {
            BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
                // Stretch to the panel width, but never below TABLE_MIN_WIDTH (then pan).
                val tableWidth = maxOf(maxWidth, TABLE_MIN_WIDTH)
                val scroll = rememberScrollState()
                Box(modifier = Modifier.fillMaxSize().horizontalScroll(scroll)) {
                    Column(modifier = Modifier.width(tableWidth).fillMaxHeight()) {
                        RecurringHeader(sortKey, sortAsc, onSort)
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        Box(modifier = Modifier.fillMaxWidth().weight(1f)) {
                            when {
                                state.loading && state.items.isEmpty() ->
                                    RecurringSkeleton(modifier = Modifier.align(Alignment.TopStart))
                                state.error != null -> Text(
                                    text = "Error: ${state.error}",
                                    color = MaterialTheme.colorScheme.error,
                                    style = MaterialTheme.typography.bodyMedium,
                                    modifier = Modifier.align(Alignment.Center).padding(24.dp),
                                )
                                state.items.isEmpty() -> Text(
                                    text = "No recurring jobs registered",
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    style = MaterialTheme.typography.bodyMedium,
                                    modifier = Modifier.align(Alignment.Center).padding(24.dp),
                                )
                                visibleItems.isEmpty() -> Text(
                                    text = "No recurring jobs match \"$needle\"",
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    style = MaterialTheme.typography.bodyMedium,
                                    modifier = Modifier.align(Alignment.Center).padding(24.dp),
                                )
                                else -> LazyColumn(modifier = Modifier.fillMaxSize()) {
                                    items(visibleItems, key = { it.id }) { row ->
                                        RecurringRow(
                                            job = row,
                                            busy = state.togglingId == row.id,
                                            triggering = state.triggeringId == row.id,
                                            runEnabled = state.triggeringId == null,
                                            ageAbsolute = state.ageAbsolute,
                                            onToggle = { enable -> component.onToggleClicked(row.id, enable) },
                                            onRun = { component.onRunNowClicked(row.id) },
                                        )
                                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

// Slim filter row under the header (mirrors the JobList filter rows). One free-text field that
// narrows the table to definitions whose name (ID) or payload type contains the query,
// case-insensitively.
@Composable
private fun RecurringSearchBar(value: String, onValueChange: (String) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = true,
            placeholder = { Text("Search by name or type", style = MaterialTheme.typography.bodySmall) },
            textStyle = MaterialTheme.typography.bodySmall,
            trailingIcon = if (value.isNotEmpty()) {
                {
                    Text(
                        text = "✕",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.clickable { onValueChange("") }.padding(8.dp),
                    )
                }
            } else null,
            modifier = Modifier.width(280.dp),
        )
    }
}

@Composable
private fun RecurringHeader(sortKey: RcSort, sortAsc: Boolean, onSort: (RcSort) -> Unit) {
    val dir = if (sortAsc) SortDirection.ASC else SortDirection.DESC
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RcHead("ID", Modifier.weight(2f), RcSort.ID, sortKey, dir, onSort)
        RcHead("Cron", Modifier.width(150.dp), RcSort.CRON, sortKey, dir, onSort)
        RcHead("Queue", Modifier.width(110.dp), RcSort.QUEUE, sortKey, dir, onSort)
        RcHead("Payload", Modifier.weight(1f), RcSort.PAYLOAD, sortKey, dir, onSort)
        RcHead("Next", Modifier.width(160.dp), RcSort.NEXT, sortKey, dir, onSort)
        RcHead("Last", Modifier.width(160.dp), RcSort.LAST, sortKey, dir, onSort)
        RcHead("Status", Modifier.width(120.dp), RcSort.STATUS, sortKey, dir, onSort)
        HeaderCell("Run", Modifier.width(96.dp))
    }
}

@Composable
private fun RcHead(
    label: String,
    modifier: Modifier,
    field: RcSort,
    sortKey: RcSort,
    direction: SortDirection,
    onSort: (RcSort) -> Unit,
) {
    SortableHeaderCell(
        label = label,
        modifier = modifier,
        active = sortKey == field,
        direction = direction,
        onClick = { onSort(field) },
    )
}

@Composable
private fun HeaderCell(label: String, modifier: Modifier) {
    Text(
        text = label.uppercase(),
        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Medium, letterSpacing = 0.6.sp),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier,
    )
}

@Composable
private fun RecurringRow(
    job: RecurringJobDto,
    busy: Boolean,
    triggering: Boolean,
    runEnabled: Boolean,
    ageAbsolute: Boolean,
    onToggle: (Boolean) -> Unit,
    onRun: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    // Disabled rows read muted so the operator can scan which definitions are paused.
    val fg = if (job.enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .hoverable(interaction)
            .background(if (hovered) MaterialTheme.colorScheme.surfaceContainerLow else Color.Transparent)
            .heightIn(min = 48.dp)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CopyableText(
            text = job.id,
            style = MaterialTheme.typography.bodyMedium,
            color = fg,
            modifier = Modifier.weight(2f),
        )
        Text(
            text = job.cron,
            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
            color = fg,
            modifier = Modifier.width(150.dp),
        )
        Text(job.queue, style = MaterialTheme.typography.bodyMedium, color = fg, modifier = Modifier.width(110.dp))
        Text(
            text = job.payloadType.substringAfterLast('.'),
            style = MaterialTheme.typography.bodySmall,
            color = fg,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = if (ageAbsolute) formatDateTime(job.nextTriggerAt) else timeAgoOrSoon(job.nextTriggerAt),
            style = MaterialTheme.typography.bodySmall,
            color = fg,
            modifier = Modifier.width(160.dp),
        )
        Text(
            text = job.lastTriggeredAt?.let { if (ageAbsolute) formatDateTime(it) else timeAgo(it) } ?: "never",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(160.dp),
        )
        Box(modifier = Modifier.width(120.dp), contentAlignment = Alignment.CenterStart) {
            Switch(
                checked = job.enabled,
                onCheckedChange = { onToggle(it) },
                enabled = !busy,
            )
        }
        Box(modifier = Modifier.width(96.dp), contentAlignment = Alignment.CenterStart) {
            // Manual one-off fire — independent of the enabled switch, so even a paused
            // definition can be run on demand.
            OutlinedButton(
                onClick = onRun,
                enabled = runEnabled,
                shape = MaterialTheme.shapes.small,
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
            ) { Text(if (triggering) "Running…" else "Run") }
        }
    }
}

@Composable
private fun RecurringSkeleton(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxWidth().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        repeat(8) {
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                SkeletonBar(200.dp)
                SkeletonBar(120.dp)
                SkeletonBar(90.dp)
                SkeletonBar(240.dp)
                SkeletonBar(90.dp)
                SkeletonBar(90.dp)
            }
        }
    }
}

@Composable
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

private val RC_DEFAULT_SORT = RcSort.NEXT

// Text + Next sort ascending by default; Last (most-recent) and Status (enabled-first) descending.
private fun rcDefaultAsc(key: RcSort): Boolean = key != RcSort.LAST && key != RcSort.STATUS

private fun sortRecurring(items: List<RecurringJobDto>, key: RcSort, asc: Boolean): List<RecurringJobDto> {
    val cmp: Comparator<RecurringJobDto> = when (key) {
        RcSort.ID -> compareBy { it.id }
        RcSort.CRON -> compareBy { it.cron }
        RcSort.QUEUE -> compareBy { it.queue }
        RcSort.PAYLOAD -> compareBy { it.payloadType }
        RcSort.NEXT -> compareBy { it.nextTriggerAt }
        RcSort.LAST -> compareBy { it.lastTriggeredAt }
        RcSort.STATUS -> compareBy { it.enabled }
    }
    val sorted = items.sortedWith(cmp)
    return if (asc) sorted else sorted.reversed()
}
