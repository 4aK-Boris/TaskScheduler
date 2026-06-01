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
import cs.trade.scheduler.core.frontend.theme.schedulerColors
import cs.trade.scheduler.dashboard.web.data.connection.ConnectionStatus
import cs.trade.scheduler.dashboard.web.presentation.screens.jobdetail.JobDetailContent
import cs.trade.scheduler.dashboard.web.presentation.screens.joblist.JobListContent
import cs.trade.scheduler.dashboard.web.presentation.screens.recurring.RecurringListContent
import cs.trade.scheduler.dashboard.web.presentation.screens.stats.StatsContent
import cs.trade.scheduler.dashboard.web.presentation.screens.types.TypesContent
import cs.trade.scheduler.dashboard.web.presentation.screens.typesstats.TypeStatsContent
import cs.trade.scheduler.dashboard.web.presentation.screens.upcoming.UpcomingContent
import cs.trade.scheduler.dashboard.web.presentation.screens.workers.WorkersContent
import androidx.compose.foundation.Canvas
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import com.arkivanov.decompose.extensions.compose.stack.animation.stackAnimation
import com.arkivanov.decompose.extensions.compose.stack.animation.fade
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.border
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.RepeatMode

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
                onUpcoming = component::onNavigateToUpcoming,
                onStats = component::onNavigateToStats,
                onWorkers = component::onNavigateToWorkers,
                onTypes = component::onNavigateToTypes,
                onTypeStats = component::onNavigateToTypeStats,
                onToggleTheme = component::onToggleTheme,
                onJumpToJob = component::onJumpToJob,
            )
            HorizontalDivider()
            Children(
                stack = component.stack,
                animation = stackAnimation(fade(animationSpec = tween(durationMillis = 380, easing = FastOutSlowInEasing))),
            ) {
                when (val child = it.instance) {
                    is RootComponent.Child.JobList -> JobListContent(child.component)
                    is RootComponent.Child.JobDetail -> JobDetailContent(child.component)
                    is RootComponent.Child.RecurringList -> RecurringListContent(child.component)
                    is RootComponent.Child.Upcoming -> UpcomingContent(child.component)
                    is RootComponent.Child.Stats -> StatsContent(child.component)
                    is RootComponent.Child.Workers -> WorkersContent(child.component)
                    is RootComponent.Child.Types -> TypesContent(child.component)
                    is RootComponent.Child.TypeStats -> TypeStatsContent(child.component)
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
    onUpcoming: () -> Unit,
    onStats: () -> Unit,
    onWorkers: () -> Unit,
    onTypes: () -> Unit,
    onTypeStats: () -> Unit,
    onToggleTheme: () -> Unit,
    onJumpToJob: (String) -> Unit,
) {
    Surface(color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().height(64.dp).padding(horizontal = 24.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(20.dp), verticalAlignment = Alignment.CenterVertically) {
                Brand()
                Box(
                    modifier = Modifier
                        .height(26.dp)
                        .width(1.dp)
                        .background(MaterialTheme.colorScheme.outlineVariant),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.CenterVertically) {
                    NavLink("Jobs", active is RootComponent.Config.JobList || active is RootComponent.Config.JobDetail, onJobs)
                    NavLink("Upcoming", active is RootComponent.Config.Upcoming, onUpcoming)
                    NavLink("Recurring", active is RootComponent.Config.RecurringList, onRecurring)
                    NavLink("Workers", active is RootComponent.Config.Workers, onWorkers)
                    NavLink("Types", active is RootComponent.Config.Types, onTypes)
                    NavLink("Type Stats", active is RootComponent.Config.TypeStats, onTypeStats)
                    NavLink("Stats", active is RootComponent.Config.Stats, onStats)
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                JobIdSearchBox(onSubmit = onJumpToJob)
                ConnectionBadge(connection)
                ThemeToggle(isDark = isDark, onToggle = onToggleTheme)
            }
        }
    }
}

@Composable
private fun JobIdSearchBox(onSubmit: (String) -> Unit) {
    // Compact "command-bar": magnifier + monospace UUID input in a crisp hairline field.
    // Enter navigates to JobDetail (which surfaces "not found" for a bad id).
    var value by remember { mutableStateOf("") }
    val shape = MaterialTheme.shapes.small
    Row(
        modifier = Modifier
            .width(260.dp)
            .height(34.dp)
            .background(MaterialTheme.colorScheme.surfaceContainerHighest, shape)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, shape)
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        SearchGlyph(MaterialTheme.colorScheme.onSurfaceVariant)
        Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
            if (value.isEmpty()) {
                Text(
                    text = "Find job by ID",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            BasicTextField(
                value = value,
                onValueChange = { value = it },
                singleLine = true,
                textStyle = MaterialTheme.typography.bodySmall.copy(
                    color = MaterialTheme.colorScheme.onSurface,
                    fontFamily = FontFamily.Monospace,
                ),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = {
                    val v = value.trim()
                    if (v.isNotEmpty()) {
                        onSubmit(v)
                        value = ""
                    }
                }),
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

// Magnifier glyph — ring + handle, drawn on Canvas (no icon dependency, crisp at 14dp).
@Composable
private fun SearchGlyph(color: Color) {
    Canvas(modifier = Modifier.size(14.dp)) {
        val w = size.minDimension
        val r = w * 0.32f
        val c = Offset(w * 0.40f, w * 0.40f)
        val sw = w * 0.12f
        drawCircle(color = color, radius = r, center = c, style = Stroke(width = sw))
        drawLine(
            color = color,
            start = Offset(c.x + r * 0.72f, c.y + r * 0.72f),
            end = Offset(w * 0.92f, w * 0.92f),
            strokeWidth = sw,
            cap = StrokeCap.Round,
        )
    }
}

@Composable
private fun ThemeToggle(isDark: Boolean, onToggle: () -> Unit) {
    // Sun (when dark → click to lighten) / crescent moon (when light) drawn on Canvas, not a
    // unicode glyph: skiko rendered ☀/☾ with off-centre metrics, so the button looked askew.
    // The crescent is carved with the nav's own surfaceVariant so it reads as a clean cut-out.
    val iconColor = MaterialTheme.colorScheme.onSurfaceVariant
    val carveColor = MaterialTheme.colorScheme.surfaceVariant
    IconButton(onClick = onToggle, modifier = Modifier.size(36.dp)) {
        Canvas(modifier = Modifier.size(18.dp)) {
            val c = size.minDimension / 2f
            if (isDark) {
                // Sun: solid core + eight rays.
                drawCircle(color = iconColor, radius = c * 0.48f, center = center)
                repeat(8) { i ->
                    val a = (PI.toFloat() / 4f) * i
                    val dir = Offset(cos(a), sin(a))
                    drawLine(
                        color = iconColor,
                        start = center + dir * (c * 0.66f),
                        end = center + dir * (c * 0.98f),
                        strokeWidth = c * 0.18f,
                        cap = StrokeCap.Round,
                    )
                }
            } else {
                // Crescent: a disc with an offset disc carved out in the nav background colour.
                val r = c * 0.92f
                drawCircle(color = iconColor, radius = r, center = center)
                drawCircle(color = carveColor, radius = r * 0.92f, center = center + Offset(r * 0.5f, -r * 0.22f))
            }
        }
    }
}

@Composable
private fun ConnectionBadge(status: ConnectionStatus) {
    // Three-state pill: a coloured dot + label. Semantics matter (green = ok, amber =
    // transient, red = no signal), but the colours now come from the theme's semantic
    // palette so the pill adapts to dark mode instead of staying a light pastel chip.
    val semantic = MaterialTheme.schedulerColors
    val scheme = MaterialTheme.colorScheme
    val style = when (status) {
        ConnectionStatus.CONNECTED ->
            BadgeStyle("Live", semantic.success, semantic.successContainer, semantic.onSuccessContainer)
        ConnectionStatus.RECONNECTING ->
            BadgeStyle("Reconnecting…", semantic.warning, semantic.warningContainer, semantic.onWarningContainer)
        ConnectionStatus.DISCONNECTED ->
            BadgeStyle("Disconnected", scheme.error, scheme.errorContainer, scheme.onErrorContainer)
    }
    // Breathing dot while reconnecting — signals "working on it" instead of a frozen pill.
    val transition = rememberInfiniteTransition(label = "connPulse")
    val pulse by transition.animateFloat(
        initialValue = 0.35f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(750, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "connPulseAlpha",
    )
    val dotAlpha = if (status == ConnectionStatus.RECONNECTING) pulse else 1f
    Row(
        modifier = Modifier
            .background(style.container, MaterialTheme.shapes.small)
            .padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(modifier = Modifier.size(8.dp).background(style.dot.copy(alpha = dotAlpha), CircleShape))
        Text(text = style.label, style = MaterialTheme.typography.labelMedium, color = style.onContainer)
    }
}

private data class BadgeStyle(val label: String, val dot: Color, val container: Color, val onContainer: Color)

@Composable
private fun NavLink(label: String, isActive: Boolean, onClick: () -> Unit) {
    // Uppercase, tracked label with a short cobalt tick under the active section — reads as a
    // precise tab rather than a plain text link.
    // Animated: label colour fades and the cobalt tick grows/shrinks when the active section changes.
    val color by animateColorAsState(
        if (isActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        animationSpec = tween(durationMillis = 320, easing = FastOutSlowInEasing),
        label = "navLinkColor",
    )
    val tickWidth by animateDpAsState(
        if (isActive) 18.dp else 0.dp,
        animationSpec = tween(durationMillis = 320, easing = FastOutSlowInEasing),
        label = "navLinkTick",
    )
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        modifier = Modifier.clickable(onClick = onClick).padding(horizontal = 10.dp, vertical = 6.dp),
    ) {
        Text(
            text = label.uppercase(),
            style = MaterialTheme.typography.labelLarge.copy(
                fontWeight = if (isActive) FontWeight.SemiBold else FontWeight.Medium,
                letterSpacing = 0.8.sp,
            ),
            color = color,
        )
        Spacer(Modifier.height(5.dp))
        Box(
            modifier = Modifier
                .height(2.dp)
                .width(tickWidth)
                .background(MaterialTheme.colorScheme.primary),
        )
    }
}

@Composable
private fun Brand() {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        BrandMark()
        Text(
            text = "TASKSCHEDULER",
            style = MaterialTheme.typography.titleMedium.copy(
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.5.sp,
            ),
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

// Brand glyph — cobalt tile + white clock, drawn on Canvas (matches the page favicon).
@Composable
private fun BrandMark() {
    val accent = MaterialTheme.colorScheme.primary
    val onAccent = MaterialTheme.colorScheme.onPrimary
    Canvas(modifier = Modifier.size(26.dp)) {
        val s = size.minDimension
        drawRoundRect(color = accent, cornerRadius = CornerRadius(s * 0.16f, s * 0.16f))
        val rad = s * 0.30f
        drawCircle(color = onAccent, radius = rad, center = center, style = Stroke(width = s * 0.075f))
        drawLine(onAccent, center, Offset(center.x, center.y - rad * 0.62f), strokeWidth = s * 0.075f, cap = StrokeCap.Round)
        drawLine(onAccent, center, Offset(center.x + rad * 0.5f, center.y), strokeWidth = s * 0.075f, cap = StrokeCap.Round)
    }
}
