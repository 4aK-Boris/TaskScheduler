package cs.trade.scheduler.dashboard.web.presentation.root

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.arkivanov.decompose.extensions.compose.stack.Children
import com.arkivanov.decompose.extensions.compose.subscribeAsState
import cs.trade.scheduler.core.frontend.theme.SchedulerTheme
import cs.trade.scheduler.dashboard.web.data.connection.ConnectionStatus
import cs.trade.scheduler.dashboard.web.presentation.screens.jobdetail.JobDetailContent
import cs.trade.scheduler.dashboard.web.presentation.screens.joblist.JobListContent
import cs.trade.scheduler.dashboard.web.presentation.screens.recurring.RecurringListContent
import cs.trade.scheduler.dashboard.web.presentation.screens.stats.StatsContent
import cs.trade.scheduler.dashboard.web.presentation.screens.types.TypesContent
import cs.trade.scheduler.dashboard.web.presentation.screens.workers.WorkersContent

@Composable
public fun RootContent(component: RootComponent) {
    val isDark by component.isDarkTheme.subscribeAsState()
    SchedulerTheme(isDark = isDark) {
        val stack by component.stack.subscribeAsState()
        val connection by component.connection.subscribeAsState()
        val currentConfig = stack.active.configuration
        Column(modifier = Modifier.fillMaxSize()) {
            SectionNav(
                active = currentConfig,
                connection = connection,
                isDark = isDark,
                onJobs = component::onNavigateToJobs,
                onRecurring = component::onNavigateToRecurring,
                onStats = component::onNavigateToStats,
                onWorkers = component::onNavigateToWorkers,
                onTypes = component::onNavigateToTypes,
                onToggleTheme = component::onToggleTheme,
                onJumpToJob = component::onJumpToJob,
            )
            HorizontalDivider()
            Children(stack = component.stack) {
                when (val child = it.instance) {
                    is RootComponent.Child.JobList -> JobListContent(child.component)
                    is RootComponent.Child.JobDetail -> JobDetailContent(child.component)
                    is RootComponent.Child.RecurringList -> RecurringListContent(child.component)
                    is RootComponent.Child.Stats -> StatsContent(child.component)
                    is RootComponent.Child.Workers -> WorkersContent(child.component)
                    is RootComponent.Child.Types -> TypesContent(child.component)
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SectionNav(
    active: RootComponent.Config,
    connection: ConnectionStatus,
    isDark: Boolean,
    onJobs: () -> Unit,
    onRecurring: () -> Unit,
    onStats: () -> Unit,
    onWorkers: () -> Unit,
    onTypes: () -> Unit,
    onToggleTheme: () -> Unit,
    onJumpToJob: (String) -> Unit,
) {
    Surface(color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(24.dp), verticalAlignment = Alignment.CenterVertically) {
                NavLink(
                    label = "Jobs",
                    isActive = active is RootComponent.Config.JobList || active is RootComponent.Config.JobDetail,
                    onClick = onJobs,
                )
                NavLink(
                    label = "Recurring",
                    isActive = active is RootComponent.Config.RecurringList,
                    onClick = onRecurring,
                )
                NavLink(
                    label = "Workers",
                    isActive = active is RootComponent.Config.Workers,
                    onClick = onWorkers,
                )
                NavLink(
                    label = "Types",
                    isActive = active is RootComponent.Config.Types,
                    onClick = onTypes,
                )
                NavLink(
                    label = "Stats",
                    isActive = active is RootComponent.Config.Stats,
                    onClick = onStats,
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                JobIdSearchBox(onSubmit = onJumpToJob)
                ConnectionBadge(connection)
                ThemeToggle(isDark = isDark, onToggle = onToggleTheme)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun JobIdSearchBox(onSubmit: (String) -> Unit) {
    // Single-line text box mounted in the nav. UUIDs from log lines paste cleanly; Enter
    // navigates to JobDetail which surfaces "Job not found" if the id is invalid/missing.
    // Compact width (240dp) — long enough to fit a full UUID without forcing horizontal
    // scroll, narrow enough to stay out of the way.
    var value by remember { mutableStateOf("") }
    OutlinedTextField(
        value = value,
        onValueChange = { value = it },
        placeholder = { Text("Job ID…", style = MaterialTheme.typography.bodySmall) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = {
            val v = value.trim()
            if (v.isNotEmpty()) {
                onSubmit(v)
                value = ""
            }
        }),
        textStyle = MaterialTheme.typography.bodySmall,
        modifier = Modifier.width(240.dp),
    )
}

@Composable
private fun ThemeToggle(isDark: Boolean, onToggle: () -> Unit) {
    // Tiny pill — sun/moon glyph in a coloured background. Avoids depending on an icon
    // library; emoji is enough signal for an operator.
    IconButton(onClick = onToggle, modifier = Modifier.size(36.dp)) {
        Text(
            text = if (isDark) "☀" else "☾",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ConnectionBadge(status: ConnectionStatus) {
    // Three-state pill: a coloured dot + label. Colours hard-coded (not from M3 scheme)
    // because semantics matter: green = ok regardless of theme, amber = transient,
    // red = no signal. M3 palette doesn't map to those concepts cleanly.
    val (label, dot, surface) = when (status) {
        ConnectionStatus.CONNECTED -> Triple("Live", Color(0xFF22C55E), Color(0xFFE7F8EF))
        ConnectionStatus.RECONNECTING -> Triple("Reconnecting...", Color(0xFFF59E0B), Color(0xFFFEF3C7))
        ConnectionStatus.DISCONNECTED -> Triple("Disconnected", Color(0xFFEF4444), Color(0xFFFEE2E2))
    }
    Row(
        modifier = Modifier
            .background(surface, RoundedCornerShape(12.dp))
            .padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(modifier = Modifier.size(8.dp).background(dot, CircleShape))
        Text(text = label, style = MaterialTheme.typography.labelMedium, color = Color(0xFF1F2937))
    }
}

@Composable
private fun NavLink(label: String, isActive: Boolean, onClick: () -> Unit) {
    val color = if (isActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
    val style = if (isActive) MaterialTheme.typography.titleMedium else MaterialTheme.typography.bodyMedium
    Text(
        text = label,
        style = style,
        color = color,
        modifier = Modifier
            .clickable(onClick = onClick)
            .padding(vertical = 2.dp, horizontal = 4.dp),
    )
}
