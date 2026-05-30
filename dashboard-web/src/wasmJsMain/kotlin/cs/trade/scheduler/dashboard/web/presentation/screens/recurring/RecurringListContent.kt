package cs.trade.scheduler.dashboard.web.presentation.screens.recurring

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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
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
import cs.trade.scheduler.dashboard.web.presentation.components.formatClock
import cs.trade.scheduler.dashboard.web.presentation.components.timeAgo
import cs.trade.scheduler.shared.dto.RecurringJobDto
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Instant

// Fixed columns (860) + a floor for the flexible Payload column. Above this the table fills the
// panel (Payload absorbs the slack); below it the operator pans horizontally.
private val TABLE_MIN_WIDTH = 1040.dp

@Composable
public fun RecurringListContent(component: RecurringListComponent) {
    val state by component.model.subscribeAsState()
    Column(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        PageHeader(title = "Recurring", count = state.items.size.toLong()) {
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
        DashboardPanel {
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
