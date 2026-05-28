package cs.trade.scheduler.dashboard.web.presentation.screens.typesstats

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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.arkivanov.decompose.extensions.compose.subscribeAsState
import cs.trade.scheduler.shared.dto.TypeStatsDto
import cs.trade.scheduler.shared.dto.TypeStatsRange

@OptIn(ExperimentalMaterial3Api::class)
@Composable
public fun TypeStatsContent(component: TypeStatsComponent) {
    val state by component.model.subscribeAsState()
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Type Stats (${state.items.size})") },
                actions = {
                    RangeSelector(
                        current = state.range,
                        onSelected = component::onRangeChanged,
                    )
                    Spacer(Modifier.width(8.dp))
                    OutlinedButton(onClick = component::onBackClicked) { Text("Back") }
                    Spacer(Modifier.width(8.dp))
                    OutlinedButton(onClick = component::onRefresh) { Text("Refresh") }
                    Spacer(Modifier.width(8.dp))
                },
            )
        },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            if (state.error != null) {
                Text(
                    text = state.error!!,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }
            TypeStatsHeader()
            HorizontalDivider()
            Box(modifier = Modifier.fillMaxSize()) {
                when {
                    state.loading && state.items.isEmpty() -> CircularProgressIndicator(
                        modifier = Modifier.align(Alignment.Center),
                    )
                    state.items.isEmpty() -> Text(
                        text = "No data in selected window",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.align(Alignment.Center).padding(16.dp),
                    )
                    else -> LazyColumn(modifier = Modifier.fillMaxSize()) {
                        items(
                            state.items,
                            // (payloadType, queue) is the natural grouping key — pair them
                            // so two different queues for the same type render distinct
                            // composables.
                            key = { "${it.payloadType}|${it.queue}" },
                        ) { row ->
                            TypeStatsRow(row)
                            HorizontalDivider()
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RangeSelector(current: TypeStatsRange, onSelected: (TypeStatsRange) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = it },
        modifier = Modifier.width(160.dp),
    ) {
        OutlinedTextField(
            value = current.label(),
            onValueChange = { /* read-only — selection drives the value */ },
            readOnly = true,
            singleLine = true,
            label = { Text("Range") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier
                .fillMaxWidth()
                .menuAnchor(MenuAnchorType.PrimaryNotEditable),
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            TypeStatsRange.entries.forEach { range ->
                DropdownMenuItem(
                    text = { Text(range.label()) },
                    onClick = {
                        onSelected(range)
                        expanded = false
                    },
                )
            }
        }
    }
}

@Composable
private fun TypeStatsHeader() {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("Type",      style = headerStyle(), modifier = Modifier.width(280.dp))
        Text("Queue",     style = headerStyle(), modifier = Modifier.width(120.dp))
        Text("Success",   style = headerStyle(), modifier = Modifier.width(80.dp))
        Text("Failed",    style = headerStyle(), modifier = Modifier.width(80.dp))
        Text("Cancelled", style = headerStyle(), modifier = Modifier.width(80.dp))
        Text("Retries",   style = headerStyle(), modifier = Modifier.width(80.dp))
        Text("Avg ms",    style = headerStyle(), modifier = Modifier.width(80.dp))
        Text("Min ms",    style = headerStyle(), modifier = Modifier.width(80.dp))
        Text("Max ms",    style = headerStyle(), modifier = Modifier.width(80.dp))
        Text("P95 ms",    style = headerStyle(), modifier = Modifier.width(80.dp))
    }
}

@Composable
private fun headerStyle() = MaterialTheme.typography.labelSmall.copy(
    color = MaterialTheme.colorScheme.onSurfaceVariant,
)

@Composable
private fun TypeStatsRow(row: TypeStatsDto) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = row.payloadType,
            style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
            modifier = Modifier.width(280.dp),
        )
        Text(row.queue, style = MaterialTheme.typography.bodySmall, modifier = Modifier.width(120.dp))
        Text(row.successCount.toString(),   style = numberStyle(), modifier = Modifier.width(80.dp))
        Text(row.failedCount.toString(),    style = numberStyle(failed = row.failedCount > 0), modifier = Modifier.width(80.dp))
        Text(row.cancelledCount.toString(), style = numberStyle(), modifier = Modifier.width(80.dp))
        Text(row.retryCount.toString(),     style = numberStyle(), modifier = Modifier.width(80.dp))
        Text(row.avgDurationMs.fmt(),       style = numberStyle(), modifier = Modifier.width(80.dp))
        Text(row.minDurationMs.fmt(),       style = numberStyle(), modifier = Modifier.width(80.dp))
        Text(row.maxDurationMs.fmt(),       style = numberStyle(), modifier = Modifier.width(80.dp))
        Text(row.p95DurationMs.fmt(),       style = numberStyle(), modifier = Modifier.width(80.dp))
    }
}

@Composable
private fun numberStyle(failed: Boolean = false) =
    if (failed) MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.error)
    else MaterialTheme.typography.bodyMedium

private fun Long?.fmt(): String = this?.toString() ?: "-"
