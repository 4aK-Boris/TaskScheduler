package cs.trade.scheduler.dashboard.web.presentation.screens.upcoming

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
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import cs.trade.scheduler.dashboard.web.presentation.components.CopyableText
import cs.trade.scheduler.dashboard.web.presentation.components.DashboardPanel
import cs.trade.scheduler.dashboard.web.presentation.components.PageHeader
import cs.trade.scheduler.dashboard.web.presentation.components.SettingsMenu
import cs.trade.scheduler.dashboard.web.presentation.components.SkeletonBar
import cs.trade.scheduler.dashboard.web.presentation.components.StateChip
import cs.trade.scheduler.dashboard.web.presentation.components.formatDateTime
import cs.trade.scheduler.dashboard.web.presentation.components.timeUntil
import cs.trade.scheduler.shared.dto.JobView

// Fixed columns (scheduled 160 + state 140 + queue 120 + id 130 = 550) + a floor for the flexible
// Name column. Above this the table fills the panel; below it the operator pans horizontally.
private val TABLE_MIN_WIDTH = 900.dp

@Composable
public fun UpcomingContent(component: UpcomingComponent) {
    val state by component.model.subscribeAsState()
    Column(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        PageHeader(title = "Upcoming", count = state.items.size.toLong()) {
            WindowSegments(current = state.windowMinutes, onSelected = component::onWindowChanged)
            SettingsMenu(
                autoRefreshSeconds = state.autoRefreshSeconds,
                onAutoRefreshChanged = component::onAutoRefreshChanged,
                timeSectionLabel = "Scheduled column",
                relativeLabel = "Relative (in 18m)",
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
                        UpcomingHeader()
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        Box(modifier = Modifier.fillMaxWidth().weight(1f)) {
                            when {
                                state.loading && state.items.isEmpty() ->
                                    UpcomingSkeleton(modifier = Modifier.align(Alignment.TopStart))
                                state.error != null -> Text(
                                    text = "Error: ${state.error}",
                                    color = MaterialTheme.colorScheme.error,
                                    style = MaterialTheme.typography.bodyMedium,
                                    modifier = Modifier.align(Alignment.Center).padding(24.dp),
                                )
                                state.items.isEmpty() -> Text(
                                    text = "Nothing scheduled in the selected window",
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    style = MaterialTheme.typography.bodyMedium,
                                    modifier = Modifier.align(Alignment.Center).padding(24.dp),
                                )
                                else -> LazyColumn(modifier = Modifier.fillMaxSize()) {
                                    items(state.items, key = { it.id }) { row ->
                                        UpcomingRow(
                                            job = row,
                                            timeAbsolute = state.timeAbsolute,
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
        }
    }
}

// Look-ahead window picker. No "Off" — this screen is the upcoming view; the window only narrows it.
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun WindowSegments(current: Int, onSelected: (Int) -> Unit) {
    val options: List<Pair<String, Int>> = listOf("1h" to 60, "6h" to 360, "24h" to 1440, "3d" to 4320)
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        options.forEach { (label, minutes) ->
            FilterChip(
                selected = current == minutes,
                onClick = { onSelected(minutes) },
                label = { Text(label) },
                colors = FilterChipDefaults.filterChipColors(),
            )
        }
    }
}

@Composable
private fun UpcomingHeader() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        HeaderCell("Scheduled", Modifier.width(160.dp))
        HeaderCell("State", Modifier.width(140.dp))
        HeaderCell("Queue", Modifier.width(120.dp))
        HeaderCell("Name", Modifier.weight(1f))
        HeaderCell("ID", Modifier.width(130.dp))
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
private fun UpcomingRow(job: JobView, timeAbsolute: Boolean, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .hoverable(interaction)
            .clickable(onClick = onClick)
            .background(if (hovered) MaterialTheme.colorScheme.surfaceContainerLow else Color.Transparent)
            .heightIn(min = 48.dp)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // The agenda's headline value — when the job is due. Future → "in 18m"; absolute on toggle.
        Text(
            text = job.scheduledAt?.let { if (timeAbsolute) formatDateTime(it) else timeUntil(it) } ?: "—",
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.width(160.dp),
        )
        Box(modifier = Modifier.width(140.dp)) { StateChip(job.state) }
        Text(job.queue, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.width(120.dp))
        Box(modifier = Modifier.weight(1f).padding(end = 16.dp)) {
            CopyableText(
                text = job.payloadType.substringAfterLast('.'),
                copyValue = job.payloadType,
                tooltip = job.payloadType,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.fillMaxWidth(),
            )
        }
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

@Composable
private fun UpcomingSkeleton(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxWidth().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        repeat(8) {
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                SkeletonBar(120.dp)
                SkeletonBar(90.dp)
                SkeletonBar(90.dp)
                SkeletonBar(220.dp)
                SkeletonBar(90.dp)
            }
        }
    }
}
