package cs.trade.scheduler.dashboard.web.presentation.screens.types

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
import androidx.compose.material3.Surface
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
import cs.trade.scheduler.dashboard.web.presentation.components.PausedBadge
import cs.trade.scheduler.dashboard.web.presentation.components.timeAgo
import cs.trade.scheduler.shared.dto.TypePauseDto

@OptIn(ExperimentalMaterial3Api::class)
@Composable
public fun TypesContent(component: TypesComponent) {
    val state by component.model.subscribeAsState()
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Paused Types (${state.items.size})") },
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
            PauseFormPanel(
                typeValue = state.pauseFormType,
                reasonValue = state.pauseFormReason,
                submitting = state.submitting,
                knownTypes = state.knownTypes,
                pausedTypes = state.items.map { it.payloadType }.toSet(),
                onTypeChange = component::onPauseFormTypeChanged,
                onReasonChange = component::onPauseFormReasonChanged,
                onSubmit = component::onPauseSubmit,
            )
            if (state.error != null) {
                Text(
                    text = state.error!!,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }
            HorizontalDivider()
            TypesHeader()
            HorizontalDivider()
            Box(modifier = Modifier.fillMaxSize()) {
                when {
                    state.loading && state.items.isEmpty() -> CircularProgressIndicator(
                        modifier = Modifier.align(Alignment.Center),
                    )
                    state.items.isEmpty() -> Text(
                        text = "No types currently paused",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.align(Alignment.Center).padding(16.dp),
                    )
                    else -> LazyColumn(modifier = Modifier.fillMaxSize()) {
                        items(state.items, key = { it.payloadType }) { row ->
                            PausedRow(
                                row = row,
                                busy = state.unpausingType == row.payloadType,
                                onUnpause = { component.onUnpauseClicked(row.payloadType) },
                            )
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
private fun PauseFormPanel(
    typeValue: String,
    reasonValue: String,
    submitting: Boolean,
    knownTypes: List<String>,
    pausedTypes: Set<String>,
    onTypeChange: (String) -> Unit,
    onReasonChange: (String) -> Unit,
    onSubmit: () -> Unit,
) {
    // Surface tint differentiates the form panel from the list below without a heavy
    // card border. The bigger surfaceVariant background tells the user "this is input,
    // not data".
    Surface(color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Suggestions = known types not already paused. We DON'T hide paused ones
            // from the global list (they're still valid input — re-pause updates the
            // row's reason/actor), but they sink to the bottom by being filtered out
            // of the typed-prefix match so non-paused suggestions take precedence.
            val suggestions = remember(typeValue, knownTypes, pausedTypes) {
                val needle = typeValue.trim()
                val match: (String) -> Boolean = when {
                    needle.isEmpty() -> { _ -> true }
                    else -> { t -> t.contains(needle, ignoreCase = true) }
                }
                knownTypes.filter(match).take(MAX_SUGGESTIONS)
            }
            var expanded by remember { mutableStateOf(false) }
            ExposedDropdownMenuBox(
                expanded = expanded && suggestions.isNotEmpty() && !submitting,
                onExpandedChange = { if (!submitting) expanded = it },
                modifier = Modifier.width(360.dp),
            ) {
                OutlinedTextField(
                    value = typeValue,
                    onValueChange = {
                        onTypeChange(it)
                        // Reopen the menu on every typed character so the filtered list
                        // becomes visible without an extra click.
                        expanded = true
                    },
                    label = { Text("Payload type (FQN)") },
                    singleLine = true,
                    enabled = !submitting,
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .menuAnchor(MenuAnchorType.PrimaryEditable, enabled = !submitting),
                )
                ExposedDropdownMenu(
                    expanded = expanded && suggestions.isNotEmpty() && !submitting,
                    onDismissRequest = { expanded = false },
                ) {
                    suggestions.forEach { suggestion ->
                        DropdownMenuItem(
                            text = {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    Text(suggestion, style = MaterialTheme.typography.bodyMedium)
                                    if (suggestion in pausedTypes) PausedBadge()
                                }
                            },
                            onClick = {
                                onTypeChange(suggestion)
                                expanded = false
                            },
                        )
                    }
                }
            }
            OutlinedTextField(
                value = reasonValue,
                onValueChange = onReasonChange,
                label = { Text("Reason (optional)") },
                singleLine = true,
                enabled = !submitting,
                modifier = Modifier.width(360.dp),
            )
            Button(
                onClick = onSubmit,
                enabled = !submitting && typeValue.isNotBlank(),
            ) { Text(if (submitting) "..." else "Pause") }
        }
    }
}

private const val MAX_SUGGESTIONS = 30

@Composable
private fun TypesHeader() {
    Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Text("Payload Type", style = headerStyle(), modifier = Modifier.width(400.dp))
        Text("Since", style = headerStyle(), modifier = Modifier.width(120.dp))
        Text("Paused By", style = headerStyle(), modifier = Modifier.width(160.dp))
        Text("Reason", style = headerStyle(), modifier = Modifier.width(280.dp))
        Text("", style = headerStyle(), modifier = Modifier.width(120.dp))
    }
}

@Composable
private fun headerStyle() = MaterialTheme.typography.labelSmall.copy(
    color = MaterialTheme.colorScheme.onSurfaceVariant,
)

@Composable
private fun PausedRow(row: TypePauseDto, busy: Boolean, onUnpause: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = row.payloadType,
            style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
            modifier = Modifier.width(400.dp),
        )
        Text(
            text = timeAgo(row.pausedSince),
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.width(120.dp),
        )
        Text(
            text = row.pausedBy,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.width(160.dp),
        )
        Text(
            text = row.reason ?: "-",
            style = MaterialTheme.typography.bodySmall,
            color = if (row.reason == null) MaterialTheme.colorScheme.onSurfaceVariant
                else MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.width(280.dp),
        )
        Box(modifier = Modifier.width(120.dp)) {
            Button(onClick = onUnpause, enabled = !busy) {
                Text(if (busy) "..." else "Unpause")
            }
        }
    }
}
