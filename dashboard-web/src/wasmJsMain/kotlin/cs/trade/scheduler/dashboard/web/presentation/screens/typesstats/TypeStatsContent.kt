package cs.trade.scheduler.dashboard.web.presentation.screens.typesstats

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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.arkivanov.decompose.extensions.compose.subscribeAsState
import cs.trade.scheduler.core.frontend.theme.schedulerColors
import cs.trade.scheduler.dashboard.web.presentation.components.CopyableText
import cs.trade.scheduler.dashboard.web.presentation.components.DashboardPanel
import cs.trade.scheduler.dashboard.web.presentation.components.PageHeader
import cs.trade.scheduler.dashboard.web.presentation.components.RangeSegments
import cs.trade.scheduler.dashboard.web.presentation.components.SkeletonBar
import cs.trade.scheduler.dashboard.web.presentation.components.SortDirection
import cs.trade.scheduler.dashboard.web.presentation.components.SortableHeaderCell
import cs.trade.scheduler.shared.dto.TypeStatsDto
import cs.trade.scheduler.shared.dto.TypeStatsRange

// Fixed columns (~970) + a floor for the flexible Type column. Above this the table fills the
// panel (Type absorbs the slack); below it the operator pans horizontally.
private val TABLE_MIN_WIDTH = 1280.dp

@Composable
public fun TypeStatsContent(component: TypeStatsComponent) {
    val state by component.model.subscribeAsState()
    // Client-side sort over the fully-loaded list (this screen isn't paginated). Default: most
    // successes first, matching the server's baseline order. Three clicks on a column cycle
    // sort → flip → back to the default.
    var sortKey by remember { mutableStateOf(TS_DEFAULT_SORT) }
    var sortAsc by remember { mutableStateOf(tsDefaultAsc(TS_DEFAULT_SORT)) }
    val onSort: (TsSort) -> Unit = { key ->
        when {
            key != sortKey -> { sortKey = key; sortAsc = tsDefaultAsc(key) }
            sortAsc == tsDefaultAsc(key) -> sortAsc = !sortAsc
            else -> { sortKey = TS_DEFAULT_SORT; sortAsc = tsDefaultAsc(TS_DEFAULT_SORT) }
        }
    }
    val sortedItems = remember(state.items, sortKey, sortAsc) { sortTypeStats(state.items, sortKey, sortAsc) }
    Column(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        PageHeader(title = "Type Stats", count = state.items.size.toLong()) {
            RangeSegments(current = state.range, onSelected = component::onRangeChanged)
            OutlinedButton(onClick = component::onBackClicked, shape = MaterialTheme.shapes.small) { Text("Back") }
            OutlinedButton(onClick = component::onRefresh, shape = MaterialTheme.shapes.small) { Text("Refresh") }
        }
        DashboardPanel {
            BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
                val tableWidth = maxOf(maxWidth, TABLE_MIN_WIDTH)
                val scroll = rememberScrollState()
                Box(modifier = Modifier.fillMaxSize().horizontalScroll(scroll)) {
                    Column(modifier = Modifier.width(tableWidth).fillMaxHeight()) {
                        TypeStatsHeader(sortKey, sortAsc, onSort)
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        Box(modifier = Modifier.fillMaxWidth().weight(1f)) {
                            when {
                                state.loading && state.items.isEmpty() ->
                                    TypeStatsSkeleton(modifier = Modifier.align(Alignment.TopStart))
                                state.error != null -> Text(
                                    text = "Error: ${state.error}",
                                    color = MaterialTheme.colorScheme.error,
                                    style = MaterialTheme.typography.bodyMedium,
                                    modifier = Modifier.align(Alignment.Center).padding(24.dp),
                                )
                                state.items.isEmpty() -> Text(
                                    text = "No data in selected window",
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    style = MaterialTheme.typography.bodyMedium,
                                    modifier = Modifier.align(Alignment.Center).padding(24.dp),
                                )
                                else -> LazyColumn(modifier = Modifier.fillMaxSize()) {
                                    // (payloadType, queue) is the natural grouping key — two queues
                                    // for one type render as distinct rows.
                                    items(sortedItems, key = { "${it.payloadType}|${it.queue}" }) { row ->
                                        TypeStatsRow(row)
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
private fun TypeStatsHeader(sortKey: TsSort, sortAsc: Boolean, onSort: (TsSort) -> Unit) {
    val dir = if (sortAsc) SortDirection.ASC else SortDirection.DESC
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TsHead("Type", Modifier.weight(1f), TsSort.TYPE, sortKey, dir, onSort)
        TsHead("Queue", Modifier.width(120.dp), TsSort.QUEUE, sortKey, dir, onSort)
        // Outcome is a proportion bar, not a value — left unsortable.
        HeaderCell("Outcome", Modifier.width(150.dp))
        TsHead("Success", Modifier.width(88.dp), TsSort.SUCCESS, sortKey, dir, onSort, numeric = true)
        TsHead("Failed", Modifier.width(88.dp), TsSort.FAILED, sortKey, dir, onSort, numeric = true)
        TsHead("Cancel", Modifier.width(96.dp), TsSort.CANCELLED, sortKey, dir, onSort, numeric = true)
        TsHead("Retries", Modifier.width(84.dp), TsSort.RETRIES, sortKey, dir, onSort, numeric = true)
        TsHead("Avg ms", Modifier.width(88.dp), TsSort.AVG, sortKey, dir, onSort, numeric = true)
        TsHead("Min ms", Modifier.width(84.dp), TsSort.MIN, sortKey, dir, onSort, numeric = true)
        TsHead("Max ms", Modifier.width(84.dp), TsSort.MAX, sortKey, dir, onSort, numeric = true)
        TsHead("P95 ms", Modifier.width(88.dp), TsSort.P95, sortKey, dir, onSort, numeric = true)
    }
}

@Composable
private fun TsHead(
    label: String,
    modifier: Modifier,
    field: TsSort,
    sortKey: TsSort,
    direction: SortDirection,
    onSort: (TsSort) -> Unit,
    numeric: Boolean = false,
) {
    SortableHeaderCell(
        label = label,
        modifier = modifier,
        active = sortKey == field,
        direction = direction,
        onClick = { onSort(field) },
        numeric = numeric,
    )
}

@Composable
private fun HeaderCell(label: String, modifier: Modifier, numeric: Boolean = false) {
    Text(
        text = label.uppercase(),
        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Medium, letterSpacing = 0.6.sp),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = if (numeric) TextAlign.End else TextAlign.Start,
        modifier = modifier,
    )
}

@Composable
private fun TypeStatsRow(row: TypeStatsDto) {
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
        CopyableText(
            text = row.payloadType,
            style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
            modifier = Modifier.weight(1f),
        )
        Text(row.queue, style = MaterialTheme.typography.bodySmall, modifier = Modifier.width(120.dp))
        Box(modifier = Modifier.width(150.dp).padding(end = 16.dp)) {
            OutcomeBar(row.successCount, row.failedCount, row.cancelledCount)
        }
        NumCell(row.successCount.toString(), Modifier.width(88.dp), color = MaterialTheme.schedulerColors.success)
        NumCell(
            text = row.failedCount.toString(),
            modifier = Modifier.width(88.dp),
            color = if (row.failedCount > 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
        )
        NumCell(row.cancelledCount.toString(), Modifier.width(96.dp))
        NumCell(row.retryCount.toString(), Modifier.width(84.dp))
        NumCell(row.avgDurationMs.fmt(), Modifier.width(88.dp), mono = true)
        NumCell(row.minDurationMs.fmt(), Modifier.width(84.dp), mono = true)
        NumCell(row.maxDurationMs.fmt(), Modifier.width(84.dp), mono = true)
        NumCell(row.p95DurationMs.fmt(), Modifier.width(88.dp), mono = true)
    }
}

@Composable
private fun NumCell(
    text: String,
    modifier: Modifier,
    color: Color = MaterialTheme.colorScheme.onSurface,
    mono: Boolean = false,
) {
    val base = MaterialTheme.typography.bodyMedium
    Text(
        text = text,
        style = if (mono) base.copy(fontFamily = FontFamily.Monospace) else base,
        color = color,
        textAlign = TextAlign.End,
        modifier = modifier,
    )
}

/** Thin stacked proportion bar: success (green) / failed (red) / cancelled (grey) of the total. */
@Composable
private fun OutcomeBar(success: Long, failed: Long, cancelled: Long) {
    val total = success + failed + cancelled
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(8.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh),
    ) {
        if (total > 0L) {
            if (success > 0) Box(
                Modifier.fillMaxHeight().weight(success.toFloat())
                    .background(MaterialTheme.schedulerColors.success),
            )
            if (failed > 0) Box(
                Modifier.fillMaxHeight().weight(failed.toFloat())
                    .background(MaterialTheme.colorScheme.error),
            )
            if (cancelled > 0) Box(
                Modifier.fillMaxHeight().weight(cancelled.toFloat())
                    .background(MaterialTheme.colorScheme.onSurfaceVariant),
            )
        }
    }
}

@Composable
private fun TypeStatsSkeleton(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxWidth().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        repeat(7) {
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                SkeletonBar(240.dp)
                SkeletonBar(90.dp)
                SkeletonBar(120.dp)
                SkeletonBar(60.dp)
                SkeletonBar(60.dp)
                SkeletonBar(60.dp)
            }
        }
    }
}

private fun Long?.fmt(): String = this?.toString() ?: "—"

private enum class TsSort { TYPE, QUEUE, SUCCESS, FAILED, CANCELLED, RETRIES, AVG, MIN, MAX, P95 }

private val TS_DEFAULT_SORT = TsSort.SUCCESS

// Text columns sort A→Z by default; counts/durations highest-first. Null durations sort last on desc.
private fun tsDefaultAsc(key: TsSort): Boolean = key == TsSort.TYPE || key == TsSort.QUEUE

private fun sortTypeStats(items: List<TypeStatsDto>, key: TsSort, asc: Boolean): List<TypeStatsDto> {
    val cmp: Comparator<TypeStatsDto> = when (key) {
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
    val sorted = items.sortedWith(cmp)
    return if (asc) sorted else sorted.reversed()
}
