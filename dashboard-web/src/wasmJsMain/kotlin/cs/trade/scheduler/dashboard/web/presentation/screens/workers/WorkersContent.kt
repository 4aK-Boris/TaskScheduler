package cs.trade.scheduler.dashboard.web.presentation.screens.workers

import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import cs.trade.scheduler.core.frontend.theme.schedulerColors
import cs.trade.scheduler.dashboard.web.presentation.components.CopyableText
import cs.trade.scheduler.dashboard.web.presentation.components.DashboardPanel
import cs.trade.scheduler.dashboard.web.presentation.components.PageHeader
import cs.trade.scheduler.dashboard.web.presentation.components.SettingsMenu
import cs.trade.scheduler.dashboard.web.presentation.components.SkeletonBar
import cs.trade.scheduler.dashboard.web.presentation.components.SortDirection
import cs.trade.scheduler.dashboard.web.presentation.components.SortableHeaderCell
import cs.trade.scheduler.dashboard.web.presentation.components.formatDateTime
import cs.trade.scheduler.dashboard.web.presentation.components.timeAgo
import cs.trade.scheduler.shared.dto.WorkerDto

// Fixed columns (900) + a floor for the flexible Tags column.
private val TABLE_MIN_WIDTH = 1200.dp

@Composable
public fun WorkersContent(component: WorkersComponent) {
    val state by component.model.subscribeAsState()
    // Client-side sort over the full roster. Default: node id A→Z. Three clicks on a column
    // cycle sort → flip → back to the default.
    var sortKey by remember { mutableStateOf(WK_DEFAULT_SORT) }
    var sortAsc by remember { mutableStateOf(wkDefaultAsc(WK_DEFAULT_SORT)) }
    val onSort: (WkSort) -> Unit = { key ->
        when {
            key != sortKey -> { sortKey = key; sortAsc = wkDefaultAsc(key) }
            sortAsc == wkDefaultAsc(key) -> sortAsc = !sortAsc
            else -> { sortKey = WK_DEFAULT_SORT; sortAsc = wkDefaultAsc(WK_DEFAULT_SORT) }
        }
    }
    val sortedItems = remember(state.items, sortKey, sortAsc) { sortWorkers(state.items, sortKey, sortAsc) }
    Column(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        PageHeader(title = "Workers", count = state.items.size.toLong()) {
            SettingsMenu(
                autoRefreshSeconds = state.autoRefreshSeconds,
                onAutoRefreshChanged = component::onAutoRefreshChanged,
                timeSectionLabel = "Last HB / Uptime",
                relativeLabel = "Relative (3m ago)",
                timeAbsolute = state.timeAbsolute,
                onTimeModeChanged = component::onTimeModeChanged,
            )
            OutlinedButton(onClick = component::onBackClicked, shape = MaterialTheme.shapes.small) { Text("Back") }
            OutlinedButton(onClick = component::onRefreshClicked, shape = MaterialTheme.shapes.small) { Text("Refresh") }
        }
        DashboardPanel {
            BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
                val tableWidth = maxOf(maxWidth, TABLE_MIN_WIDTH)
                val scroll = rememberScrollState()
                Box(modifier = Modifier.fillMaxSize().horizontalScroll(scroll)) {
                    Column(modifier = Modifier.width(tableWidth).fillMaxHeight()) {
                        WorkersHeader(sortKey, sortAsc, onSort)
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        Box(modifier = Modifier.fillMaxWidth().weight(1f)) {
                            when {
                                state.loading && state.items.isEmpty() ->
                                    WorkersSkeleton(modifier = Modifier.align(Alignment.TopStart))
                                state.error != null -> Text(
                                    text = "Error: ${state.error}",
                                    color = MaterialTheme.colorScheme.error,
                                    style = MaterialTheme.typography.bodyMedium,
                                    modifier = Modifier.align(Alignment.Center).padding(24.dp),
                                )
                                state.items.isEmpty() -> Text(
                                    text = "No workers reporting",
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    style = MaterialTheme.typography.bodyMedium,
                                    modifier = Modifier.align(Alignment.Center).padding(24.dp),
                                )
                                else -> LazyColumn(modifier = Modifier.fillMaxSize()) {
                                    items(sortedItems, key = { it.nodeId }) { row ->
                                        WorkerRow(worker = row, timeAbsolute = state.timeAbsolute)
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

@Composable
private fun WorkersHeader(sortKey: WkSort, sortAsc: Boolean, onSort: (WkSort) -> Unit) {
    val dir = if (sortAsc) SortDirection.ASC else SortDirection.DESC
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        WkHead("Status", Modifier.width(100.dp), WkSort.STATUS, sortKey, dir, onSort)
        WkHead("Node ID", Modifier.width(220.dp), WkSort.NODE, sortKey, dir, onSort)
        WkHead("Host", Modifier.width(180.dp), WkSort.HOST, sortKey, dir, onSort)
        // Tags is a list — not a useful single sort key.
        HeaderCell("Tags", Modifier.weight(1f))
        WkHead("In flight", Modifier.width(160.dp), WkSort.INFLIGHT, sortKey, dir, onSort)
        WkHead("Last HB", Modifier.width(160.dp), WkSort.LASTHB, sortKey, dir, onSort)
        WkHead("Uptime", Modifier.width(160.dp), WkSort.UPTIME, sortKey, dir, onSort)
    }
}

@Composable
private fun WkHead(
    label: String,
    modifier: Modifier,
    field: WkSort,
    sortKey: WkSort,
    direction: SortDirection,
    onSort: (WkSort) -> Unit,
) {
    SortableHeaderCell(
        label = label,
        modifier = modifier,
        active = sortKey == field,
        direction = direction,
        onClick = { onSort(field) },
    )
}

private enum class WkSort { STATUS, NODE, HOST, INFLIGHT, LASTHB, UPTIME }

private val WK_DEFAULT_SORT = WkSort.NODE

// Text columns ascending; status (alive-first), in-flight, last-HB descending; uptime asc = longest first.
private fun wkDefaultAsc(key: WkSort): Boolean = when (key) {
    WkSort.NODE, WkSort.HOST, WkSort.UPTIME -> true
    else -> false
}

private fun sortWorkers(items: List<WorkerDto>, key: WkSort, asc: Boolean): List<WorkerDto> {
    val cmp: Comparator<WorkerDto> = when (key) {
        WkSort.STATUS -> compareBy { it.alive }
        WkSort.NODE -> compareBy { it.nodeId }
        WkSort.HOST -> compareBy { it.host }
        WkSort.INFLIGHT -> compareBy { it.inFlightCount }
        WkSort.LASTHB -> compareBy { it.lastHeartbeat }
        WkSort.UPTIME -> compareBy { it.startedAt }
    }
    val sorted = items.sortedWith(cmp)
    return if (asc) sorted else sorted.reversed()
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
private fun WorkerRow(worker: WorkerDto, timeAbsolute: Boolean) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .hoverable(interaction)
            .background(if (hovered) MaterialTheme.colorScheme.surfaceContainerLow else Color.Transparent)
            .heightIn(min = 52.dp)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StatusCell(alive = worker.alive, modifier = Modifier.width(100.dp))
        CopyableText(
            text = worker.nodeId,
            style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
            modifier = Modifier.width(220.dp),
        )
        CopyableText(text = worker.host, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.width(180.dp))
        Text(
            text = if (worker.tags.isEmpty()) "—" else worker.tags.joinToString(", "),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        InFlightCell(total = worker.inFlightCount, byQueue = worker.inFlightByQueue, modifier = Modifier.width(160.dp))
        Text(
            text = if (timeAbsolute) formatDateTime(worker.lastHeartbeat) else timeAgo(worker.lastHeartbeat),
            style = MaterialTheme.typography.bodySmall,
            color = if (worker.alive) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.error,
            modifier = Modifier.width(160.dp),
        )
        Text(
            text = if (timeAbsolute) formatDateTime(worker.startedAt) else timeAgo(worker.startedAt),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(160.dp),
        )
    }
}

@Composable
private fun StatusCell(alive: Boolean, modifier: Modifier) {
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .background(
                    color = if (alive) MaterialTheme.schedulerColors.success else MaterialTheme.colorScheme.error,
                    shape = CircleShape,
                ),
        )
        Text(
            text = if (alive) "Alive" else "Dead",
            style = MaterialTheme.typography.labelMedium,
            color = if (alive) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.error,
        )
    }
}

@Composable
private fun InFlightCell(total: Int, byQueue: Map<String, Int>, modifier: Modifier = Modifier) {
    // Pre-V2 workers (or ones idle since restart) come through with an empty map — show the total.
    val active = byQueue.filterValues { it > 0 }
    Column(modifier = modifier) {
        Text(total.toString(), style = MaterialTheme.typography.bodyMedium)
        if (active.isNotEmpty()) {
            Text(
                text = active.entries.sortedByDescending { it.value }.joinToString(", ") { "${it.key}=${it.value}" },
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun WorkersSkeleton(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxWidth().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        repeat(6) {
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                SkeletonBar(70.dp)
                SkeletonBar(200.dp)
                SkeletonBar(160.dp)
                SkeletonBar(160.dp)
                SkeletonBar(70.dp)
                SkeletonBar(80.dp)
            }
        }
    }
}
