package cs.trade.scheduler.dashboard.web.presentation.screens.recurring

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.arkivanov.decompose.extensions.compose.subscribeAsState
import cs.trade.scheduler.dashboard.web.presentation.components.formatClock
import cs.trade.scheduler.dashboard.web.presentation.components.timeAgo
import cs.trade.scheduler.shared.dto.RecurringJobDto
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Instant

// Minimum table width — the fixed columns (860) + a sensible floor for the flexible Payload
// column. Above this the table stretches to fill the panel (Payload absorbs the slack); below
// it the operator pans horizontally.
private val TABLE_MIN_WIDTH = 1040.dp

@Composable
public fun RecurringListContent(component: RecurringListComponent) {
    val state by component.model.subscribeAsState()
    Column(
        modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, top = 18.dp, bottom = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = "Recurring",
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onBackground,
                )
                CountPill(state.items.size.toLong())
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SettingsMenu(
                    autoRefreshSeconds = state.autoRefreshSeconds,
                    ageAbsolute = state.ageAbsolute,
                    onAutoRefreshChanged = component::onAutoRefreshChanged,
                    onAgeModeChanged = component::onAgeModeChanged,
                )
                OutlinedButton(onClick = component::onBackClicked, shape = MaterialTheme.shapes.small) { Text("Back") }
                OutlinedButton(onClick = component::onRefreshClicked, shape = MaterialTheme.shapes.small) { Text("Refresh") }
            }
        }
        Surface(
            modifier = Modifier.fillMaxSize().padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
            color = MaterialTheme.colorScheme.surface,
            shape = MaterialTheme.shapes.medium,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        ) {
            BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
                // Stretch to the panel width, but never below TABLE_MIN_WIDTH (then pan).
                val tableWidth = maxOf(maxWidth, TABLE_MIN_WIDTH)
                val scroll = rememberScrollState()
                Box(modifier = Modifier.fillMaxSize().horizontalScroll(scroll)) {
                    Column(modifier = Modifier.width(tableWidth).fillMaxHeight()) {
                        RecurringHeader()
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
                                else -> LazyColumn(modifier = Modifier.fillMaxSize()) {
                                    items(state.items, key = { it.id }) { row ->
                                        RecurringRow(
                                            job = row,
                                            busy = state.togglingId == row.id,
                                            ageAbsolute = state.ageAbsolute,
                                            onToggle = { enable -> component.onToggleClicked(row.id, enable) },
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

@Composable
private fun CountPill(count: Long) {
    Text(
        text = count.toString(),
        style = MaterialTheme.typography.labelMedium.copy(fontFamily = FontFamily.Monospace),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier
            .background(MaterialTheme.colorScheme.surfaceContainerHigh, MaterialTheme.shapes.small)
            .padding(horizontal = 8.dp, vertical = 3.dp),
    )
}

@Composable
private fun RecurringHeader() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        HeaderCell("ID", Modifier.width(220.dp))
        HeaderCell("Cron", Modifier.width(150.dp))
        HeaderCell("Queue", Modifier.width(110.dp))
        HeaderCell("Payload", Modifier.weight(1f))
        HeaderCell("Next", Modifier.width(130.dp))
        HeaderCell("Last", Modifier.width(130.dp))
        HeaderCell("Status", Modifier.width(120.dp))
    }
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
    ageAbsolute: Boolean,
    onToggle: (Boolean) -> Unit,
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
        Text(job.id, style = MaterialTheme.typography.bodyMedium, color = fg, modifier = Modifier.width(220.dp))
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
            text = if (ageAbsolute) formatClock(job.nextTriggerAt) else timeAgoOrSoon(job.nextTriggerAt),
            style = MaterialTheme.typography.bodySmall,
            color = fg,
            modifier = Modifier.width(130.dp),
        )
        Text(
            text = job.lastTriggeredAt?.let { if (ageAbsolute) formatClock(it) else timeAgo(it) } ?: "never",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(130.dp),
        )
        Box(modifier = Modifier.width(120.dp), contentAlignment = Alignment.CenterStart) {
            Switch(
                checked = job.enabled,
                onCheckedChange = { onToggle(it) },
                enabled = !busy,
            )
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
private fun SkeletonBar(width: Dp) {
    Box(
        modifier = Modifier
            .width(width)
            .height(12.dp)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh, MaterialTheme.shapes.extraSmall),
    )
}

// ---- Settings menu (Next/Last display) --------------------------------------------------

@Composable
private fun SettingsMenu(
    autoRefreshSeconds: Int?,
    ageAbsolute: Boolean,
    onAutoRefreshChanged: (Int?) -> Unit,
    onAgeModeChanged: (Boolean) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    Box {
        Box(
            modifier = Modifier
                .size(36.dp)
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, MaterialTheme.shapes.small)
                .clickable { open = true },
            contentAlignment = Alignment.Center,
        ) {
            // Cobalt when auto-refresh is on, so the active state reads at a glance.
            GearGlyph(
                if (autoRefreshSeconds != null) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            MenuSectionLabel("Auto-refresh")
            listOf<Pair<String, Int?>>(
                "Off" to null, "5 seconds" to 5, "10 seconds" to 10, "30 seconds" to 30, "1 minute" to 60,
            ).forEach { (label, secs) ->
                DropdownMenuItem(
                    text = { Text(label, style = MaterialTheme.typography.bodyMedium) },
                    onClick = { onAutoRefreshChanged(secs); open = false },
                    leadingIcon = { CheckMark(selected = autoRefreshSeconds == secs) },
                )
            }
            HorizontalDivider()
            MenuSectionLabel("Next / Last columns")
            DropdownMenuItem(
                text = { Text("Relative (2h ago)", style = MaterialTheme.typography.bodyMedium) },
                onClick = { onAgeModeChanged(false); open = false },
                leadingIcon = { CheckMark(selected = !ageAbsolute) },
            )
            DropdownMenuItem(
                text = { Text("Absolute (14:30:05)", style = MaterialTheme.typography.bodyMedium) },
                onClick = { onAgeModeChanged(true); open = false },
                leadingIcon = { CheckMark(selected = ageAbsolute) },
            )
        }
    }
}

@Composable
private fun MenuSectionLabel(label: String) {
    Text(
        text = label.uppercase(),
        style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 0.6.sp),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
    )
}

@Composable
private fun CheckMark(selected: Boolean) {
    val color = MaterialTheme.colorScheme.primary
    Canvas(modifier = Modifier.size(16.dp)) {
        if (!selected) return@Canvas
        val w = size.width
        val h = size.height
        val sw = w * 0.14f
        drawLine(color, Offset(w * 0.18f, h * 0.55f), Offset(w * 0.42f, h * 0.78f), sw, cap = StrokeCap.Round)
        drawLine(color, Offset(w * 0.42f, h * 0.78f), Offset(w * 0.82f, h * 0.28f), sw, cap = StrokeCap.Round)
    }
}

@Composable
private fun GearGlyph(color: Color) {
    Canvas(modifier = Modifier.size(16.dp)) {
        val w = size.width
        val sw = w * 0.085f
        listOf(0.28f to 0.66f, 0.5f to 0.36f, 0.72f to 0.6f).forEach { (yf, knobX) ->
            val y = size.height * yf
            drawLine(color, Offset(w * 0.15f, y), Offset(w * 0.85f, y), strokeWidth = sw, cap = StrokeCap.Round)
            drawCircle(color, radius = w * 0.09f, center = Offset(w * knobX, y))
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
