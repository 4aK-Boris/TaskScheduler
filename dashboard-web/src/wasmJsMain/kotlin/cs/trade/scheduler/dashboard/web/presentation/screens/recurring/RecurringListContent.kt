package cs.trade.scheduler.dashboard.web.presentation.screens.recurring

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.arkivanov.decompose.extensions.compose.subscribeAsState
import cs.trade.scheduler.dashboard.web.presentation.components.timeAgo
import cs.trade.scheduler.shared.dto.RecurringJobDto

@OptIn(ExperimentalMaterial3Api::class)
@Composable
public fun RecurringListContent(component: RecurringListComponent) {
    val state by component.model.subscribeAsState()
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Recurring (${state.items.size})") },
                actions = {
                    OutlinedButton(onClick = component::onBackClicked) { Text("Back") }
                    Spacer(Modifier.width(8.dp))
                    OutlinedButton(onClick = component::onRefreshClicked) { Text("Refresh") }
                    Spacer(Modifier.width(8.dp))
                },
            )
        },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            RecurringHeader()
            HorizontalDivider()
            Box(modifier = Modifier.fillMaxSize()) {
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
                        text = "No recurring jobs registered",
                        modifier = Modifier.align(Alignment.Center).padding(16.dp),
                    )
                    else -> LazyColumn(modifier = Modifier.fillMaxSize()) {
                        items(state.items, key = { it.id }) { row ->
                            RecurringRow(
                                job = row,
                                busy = state.togglingId == row.id,
                                onToggle = { component.onToggleClicked(row.id, enable = !row.enabled) },
                            )
                            HorizontalDivider()
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
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Text("ID", style = headerStyle(), modifier = Modifier.width(220.dp))
        Text("Cron", style = headerStyle(), modifier = Modifier.width(140.dp))
        Text("Queue", style = headerStyle(), modifier = Modifier.width(100.dp))
        Text("Payload", style = headerStyle(), modifier = Modifier.width(240.dp))
        Text("Next", style = headerStyle(), modifier = Modifier.width(100.dp))
        Text("Last", style = headerStyle(), modifier = Modifier.width(100.dp))
        Text("Status", style = headerStyle(), modifier = Modifier.width(110.dp))
    }
}

@Composable
private fun headerStyle() = MaterialTheme.typography.labelSmall.copy(
    color = MaterialTheme.colorScheme.onSurfaceVariant,
)

@Composable
private fun RecurringRow(job: RecurringJobDto, busy: Boolean, onToggle: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(job.id, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.width(220.dp))
        Text(
            text = job.cron,
            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
            modifier = Modifier.width(140.dp),
        )
        Text(job.queue, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.width(100.dp))
        Text(
            text = job.payloadType.substringAfterLast('.'),
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.width(240.dp),
        )
        Text(
            text = timeAgoOrSoon(job.nextTriggerAt),
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.width(100.dp),
        )
        Text(
            text = job.lastTriggeredAt?.let { timeAgo(it) } ?: "never",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(100.dp),
        )
        Box(modifier = Modifier.width(110.dp), contentAlignment = Alignment.CenterStart) {
            if (job.enabled) {
                Button(
                    onClick = onToggle,
                    enabled = !busy,
                    colors = ButtonDefaults.outlinedButtonColors(),
                ) { Text(if (busy) "..." else "Disable") }
            } else {
                Button(
                    onClick = onToggle,
                    enabled = !busy,
                ) { Text(if (busy) "..." else "Enable") }
            }
        }
    }
}

@Composable
private fun timeAgoOrSoon(instant: kotlin.time.Instant): String {
    val now = kotlin.time.Clock.System.now()
    val delta = instant - now
    return when {
        delta.isPositive() -> "in ${formatDuration(delta)}"
        else -> timeAgo(instant, now)
    }
}

private fun formatDuration(d: kotlin.time.Duration): String = when {
    d < kotlin.time.Duration.parse("PT1M") -> "${d.inWholeSeconds}s"
    d < kotlin.time.Duration.parse("PT1H") -> "${d.inWholeMinutes}m"
    d < kotlin.time.Duration.parse("P1D") -> "${d.inWholeHours}h"
    else -> "${d.inWholeDays}d"
}

@Composable
@Suppress("unused")
private fun Arrangement.spacedBy_placeholder() {}
