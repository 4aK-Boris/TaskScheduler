package cs.trade.scheduler.dashboard.web.presentation.screens.types

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
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import cs.trade.scheduler.dashboard.web.presentation.components.CopyableText
import cs.trade.scheduler.dashboard.web.presentation.components.DashboardPanel
import cs.trade.scheduler.dashboard.web.presentation.components.PageHeader
import cs.trade.scheduler.dashboard.web.presentation.components.PausedBadge
import cs.trade.scheduler.dashboard.web.presentation.components.SettingsMenu
import cs.trade.scheduler.dashboard.web.presentation.components.SkeletonBar
import cs.trade.scheduler.dashboard.web.presentation.components.formatDateTime
import cs.trade.scheduler.dashboard.web.presentation.components.timeAgo
import cs.trade.scheduler.shared.dto.TypePauseDto

// Fixed columns (770) + a floor for the flexible Payload column. Above this the table fills the
// panel (Payload absorbs the slack); below it the operator pans horizontally.
private val TABLE_MIN_WIDTH = 1140.dp
private const val MAX_SUGGESTIONS = 30

@Composable
public fun TypesContent(component: TypesComponent) {
    val state by component.model.subscribeAsState()
    Column(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        PageHeader(title = "Paused Types", count = state.items.size.toLong()) {
            SettingsMenu(
                autoRefreshSeconds = state.autoRefreshSeconds,
                onAutoRefreshChanged = component::onAutoRefreshChanged,
                timeSectionLabel = "Since column",
                relativeLabel = "Relative (2h ago)",
                timeAbsolute = state.timeAbsolute,
                onTimeModeChanged = component::onTimeModeChanged,
            )
            OutlinedButton(onClick = component::onBackClicked, shape = MaterialTheme.shapes.small) { Text("Back") }
            OutlinedButton(onClick = component::onRefreshClicked, shape = MaterialTheme.shapes.small) { Text("Refresh") }
        }
        DashboardPanel {
            Column(modifier = Modifier.fillMaxSize()) {
                PauseForm(
                    typeValue = state.pauseFormType,
                    reasonValue = state.pauseFormReason,
                    submitting = state.submitting,
                    error = state.error,
                    knownTypes = state.knownTypes,
                    pausedTypes = state.items.map { it.payloadType }.toSet(),
                    onTypeChange = component::onPauseFormTypeChanged,
                    onReasonChange = component::onPauseFormReasonChanged,
                    onSubmit = component::onPauseSubmit,
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
                    val tableWidth = maxOf(maxWidth, TABLE_MIN_WIDTH)
                    val scroll = rememberScrollState()
                    Box(modifier = Modifier.fillMaxSize().horizontalScroll(scroll)) {
                        Column(modifier = Modifier.width(tableWidth).fillMaxHeight()) {
                            TypesHeader()
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                            Box(modifier = Modifier.fillMaxWidth().weight(1f)) {
                                when {
                                    state.loading && state.items.isEmpty() ->
                                        TypesSkeleton(modifier = Modifier.align(Alignment.TopStart))
                                    state.items.isEmpty() -> Text(
                                        text = "No types currently paused",
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        style = MaterialTheme.typography.bodyMedium,
                                        modifier = Modifier.align(Alignment.Center).padding(24.dp),
                                    )
                                    else -> LazyColumn(modifier = Modifier.fillMaxSize()) {
                                        items(state.items, key = { it.payloadType }) { row ->
                                            PausedRow(
                                                row = row,
                                                busy = state.unpausingType == row.payloadType,
                                                timeAbsolute = state.timeAbsolute,
                                                onUnpause = { component.onUnpauseClicked(row.payloadType) },
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
}

/**
 * "Pause a type" composer — a tinted strip at the top of the panel so it reads as input, not data.
 * The type field autocompletes against [knownTypes]; a free-typed FQN is still valid (pausing a
 * brand-new type before any job of it exists). The submit error renders inline beneath the row.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PauseForm(
    typeValue: String,
    reasonValue: String,
    submitting: Boolean,
    error: String?,
    knownTypes: List<String>,
    pausedTypes: Set<String>,
    onTypeChange: (String) -> Unit,
    onReasonChange: (String) -> Unit,
    onSubmit: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .padding(horizontal = 16.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            text = "PAUSE A TYPE",
            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Medium, letterSpacing = 0.6.sp),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Suggestions = known types matching the typed prefix. Paused ones aren't hidden
            // (re-pausing updates the row's reason/actor) but get a PAUSED badge in the list.
            val suggestions = remember(typeValue, knownTypes) {
                val needle = typeValue.trim()
                val match: (String) -> Boolean =
                    if (needle.isEmpty()) { _ -> true } else { t -> t.contains(needle, ignoreCase = true) }
                knownTypes.filter(match).take(MAX_SUGGESTIONS)
            }
            var expanded by remember { mutableStateOf(false) }
            ExposedDropdownMenuBox(
                expanded = expanded && suggestions.isNotEmpty() && !submitting,
                onExpandedChange = { if (!submitting) expanded = it },
                modifier = Modifier.weight(1f),
            ) {
                OutlinedTextField(
                    value = typeValue,
                    onValueChange = {
                        onTypeChange(it)
                        expanded = true
                    },
                    label = { Text("Payload type (FQN)") },
                    singleLine = true,
                    enabled = !submitting,
                    shape = MaterialTheme.shapes.small,
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryEditable, enabled = !submitting),
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
                                    Text(
                                        text = suggestion,
                                        style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                                    )
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
                shape = MaterialTheme.shapes.small,
                modifier = Modifier.width(320.dp),
            )
            Button(
                onClick = onSubmit,
                enabled = !submitting && typeValue.isNotBlank(),
                shape = MaterialTheme.shapes.small,
            ) { Text(if (submitting) "Pausing…" else "Pause") }
        }
        if (error != null) {
            Text(
                text = error,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun TypesHeader() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        HeaderCell("Payload Type", Modifier.weight(1f))
        HeaderCell("Since", Modifier.width(160.dp))
        HeaderCell("Paused By", Modifier.width(190.dp))
        HeaderCell("Reason", Modifier.width(300.dp))
        HeaderCell("", Modifier.width(130.dp))
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
private fun PausedRow(row: TypePauseDto, busy: Boolean, timeAbsolute: Boolean, onUnpause: () -> Unit) {
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
        Text(
            text = if (timeAbsolute) formatDateTime(row.pausedSince) else timeAgo(row.pausedSince),
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.width(160.dp),
        )
        Text(
            text = row.pausedBy,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.width(190.dp),
        )
        Text(
            text = row.reason ?: "—",
            style = MaterialTheme.typography.bodySmall,
            color = if (row.reason == null) MaterialTheme.colorScheme.onSurfaceVariant
                else MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.width(300.dp),
        )
        Box(modifier = Modifier.width(130.dp)) {
            OutlinedButton(onClick = onUnpause, enabled = !busy, shape = MaterialTheme.shapes.small) {
                Text(if (busy) "…" else "Unpause")
            }
        }
    }
}

@Composable
private fun TypesSkeleton(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxWidth().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        repeat(6) {
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                SkeletonBar(280.dp)
                SkeletonBar(80.dp)
                SkeletonBar(120.dp)
                SkeletonBar(200.dp)
                SkeletonBar(90.dp)
            }
        }
    }
}
