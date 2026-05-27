package cs.trade.scheduler.dashboard.web.presentation.screens.workers

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.arkivanov.decompose.extensions.compose.subscribeAsState
import cs.trade.scheduler.dashboard.web.presentation.components.timeAgo
import cs.trade.scheduler.shared.dto.WorkerDto

@OptIn(ExperimentalMaterial3Api::class)
@Composable
public fun WorkersContent(component: WorkersComponent) {
    val state by component.model.subscribeAsState()
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    val alive = state.items.count { it.alive }
                    Text("Workers (${state.items.size}, $alive alive)")
                },
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
            WorkersHeader()
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
                        text = "No workers reporting",
                        modifier = Modifier.align(Alignment.Center).padding(16.dp),
                    )
                    else -> LazyColumn(modifier = Modifier.fillMaxSize()) {
                        items(state.items, key = { it.nodeId }) { row ->
                            WorkerRow(row)
                            HorizontalDivider()
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun WorkersHeader() {
    Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Text("Status", style = headerStyle(), modifier = Modifier.width(80.dp))
        Text("Node ID", style = headerStyle(), modifier = Modifier.width(220.dp))
        Text("Host", style = headerStyle(), modifier = Modifier.width(200.dp))
        Text("Tags", style = headerStyle(), modifier = Modifier.width(180.dp))
        Text("In flight", style = headerStyle(), modifier = Modifier.width(220.dp))
        Text("Last HB", style = headerStyle(), modifier = Modifier.width(110.dp))
        Text("Uptime", style = headerStyle(), modifier = Modifier.width(110.dp))
    }
}

@Composable
private fun headerStyle() = MaterialTheme.typography.labelSmall.copy(
    color = MaterialTheme.colorScheme.onSurfaceVariant,
)

@Composable
private fun WorkerRow(worker: WorkerDto) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(modifier = Modifier.width(80.dp)) {
            StatusDot(alive = worker.alive)
        }
        Text(
            text = worker.nodeId,
            style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
            modifier = Modifier.width(220.dp),
        )
        Text(worker.host, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.width(200.dp))
        Text(
            text = if (worker.tags.isEmpty()) "-" else worker.tags.joinToString(", "),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(180.dp),
        )
        InFlightCell(
            total = worker.inFlightCount,
            byQueue = worker.inFlightByQueue,
            modifier = Modifier.width(220.dp),
        )
        Text(
            text = timeAgo(worker.lastHeartbeat),
            style = MaterialTheme.typography.bodySmall,
            color = if (worker.alive) MaterialTheme.colorScheme.onSurface
                else MaterialTheme.colorScheme.error,
            modifier = Modifier.width(110.dp),
        )
        Text(
            text = timeAgo(worker.startedAt),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(110.dp),
        )
    }
}

@Composable
private fun InFlightCell(total: Int, byQueue: Map<String, Int>, modifier: Modifier = Modifier) {
    // Pre-V2 worker rows (or workers that haven't picked anything up since restart)
    // come through with an empty map — just show the legacy total in that case.
    val active = byQueue.filterValues { it > 0 }
    Column(modifier = modifier) {
        Text(
            text = total.toString(),
            style = MaterialTheme.typography.bodyMedium,
        )
        if (active.isNotEmpty()) {
            val breakdown = active.entries
                .sortedByDescending { it.value }
                .joinToString(", ") { "${it.key}=${it.value}" }
            Text(
                text = breakdown,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun StatusDot(alive: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(
            modifier = Modifier
                .size(10.dp)
                .background(
                    color = if (alive) Color(0xFF4CAF50) else MaterialTheme.colorScheme.error,
                    shape = CircleShape,
                ),
        )
        Text(
            text = if (alive) "alive" else "dead",
            style = MaterialTheme.typography.labelSmall,
            color = if (alive) MaterialTheme.colorScheme.onSurface
                else MaterialTheme.colorScheme.error,
        )
    }
}
