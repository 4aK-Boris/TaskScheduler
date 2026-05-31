package cs.trade.scheduler.dashboard.web.presentation.screens.stats

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.arkivanov.decompose.extensions.compose.subscribeAsState
import cs.trade.scheduler.core.frontend.theme.schedulerColors
import cs.trade.scheduler.dashboard.web.presentation.theme.JobStateColors
import cs.trade.scheduler.shared.JobState
import cs.trade.scheduler.shared.dto.StatsOverviewResponse
import kotlin.math.roundToInt

@Composable
public fun StatsContent(component: StatsComponent) {
    val state by component.model.subscribeAsState()
    Column(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        StatsHeader(onBack = component::onBackClicked, onRefresh = component::onRefreshClicked)
        Box(modifier = Modifier.fillMaxSize()) {
            when {
                state.loading && state.overview == null ->
                    StatsSkeleton(modifier = Modifier.align(Alignment.TopStart))
                state.error != null -> Text(
                    text = "Error: ${state.error}",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.align(Alignment.Center).padding(24.dp),
                )
                state.overview != null -> Dashboard(state.overview!!)
            }
        }
    }
}

/** Header matching the shared chrome's padding, minus the count chip — Stats has no single count. */
@Composable
private fun StatsHeader(onBack: () -> Unit, onRefresh: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, top = 18.dp, bottom = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text("Stats", style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.onBackground)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(onClick = onBack, shape = MaterialTheme.shapes.small) { Text("Back") }
            OutlinedButton(onClick = onRefresh, shape = MaterialTheme.shapes.small) { Text("Refresh") }
        }
    }
}

@Composable
private fun Dashboard(o: StatsOverviewResponse) {
    // Grow charts in once when the screen first appears — distracting on every silent refresh, so
    // keyed to first composition only.
    var go by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { go = true }
    val progress by animateFloatAsState(
        targetValue = if (go) 1f else 0f,
        animationSpec = tween(durationMillis = 700, easing = FastOutSlowInEasing),
        label = "chart-grow",
    )

    val backlog = o.enqueued + o.scheduled + o.awaitingDeps
    val rate = (o.succeeded + o.failed).takeIf { it > 0 }?.let { o.succeeded.toDouble() / it }

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())
            .padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            KpiTile(Modifier.weight(1f), "In flight", o.processing.grouped(), MaterialTheme.colorScheme.secondary, "running now")
            KpiTile(Modifier.weight(1f), "Backlog", backlog.grouped(), MaterialTheme.colorScheme.primary, "enqueued · scheduled · deps")
            KpiTile(Modifier.weight(1f), "Succeeded", o.succeeded.grouped(), MaterialTheme.schedulerColors.success, "recent")
            KpiTile(
                Modifier.weight(1f), "Failed", o.failed.grouped(),
                if (o.failed > 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface, "recent",
            )
            KpiTile(Modifier.weight(1f), "Success rate", rate.asPercent(), rateColor(rate), "succeeded / completed")
        }
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            ChartPanel("Outcomes", Modifier.weight(1f)) {
                OutcomeDonut(o.succeeded, o.failed, o.cancelled, rate, progress)
            }
            ChartPanel("Active pipeline", Modifier.weight(1.5f)) {
                PipelineBars(
                    counts = listOf(
                        JobState.SCHEDULED to o.scheduled,
                        JobState.AWAITING_DEPS to o.awaitingDeps,
                        JobState.ENQUEUED to o.enqueued,
                        JobState.PROCESSING to o.processing,
                        JobState.AWAITING_RETRY to o.awaitingRetry,
                    ),
                    progress = progress,
                )
            }
        }
        Text(
            text = "Live snapshot — updates over WebSocket",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 4.dp),
        )
    }
}

@Composable
private fun KpiTile(modifier: Modifier, label: String, value: String, valueColor: Color, sub: String) {
    Surface(
        modifier = modifier.height(116.dp),
        color = MaterialTheme.colorScheme.surface,
        shape = MaterialTheme.shapes.medium,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(modifier = Modifier.fillMaxSize().padding(18.dp), verticalArrangement = Arrangement.SpaceBetween) {
            Text(
                text = label.uppercase(),
                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Medium, letterSpacing = 0.6.sp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(text = value, style = MaterialTheme.typography.headlineMedium, color = valueColor)
            Text(text = sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun ChartPanel(title: String, modifier: Modifier, content: @Composable () -> Unit) {
    Surface(
        modifier = modifier,
        color = MaterialTheme.colorScheme.surface,
        shape = MaterialTheme.shapes.medium,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
            Text(
                text = title.uppercase(),
                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Medium, letterSpacing = 0.6.sp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            content()
        }
    }
}

/** Ring chart of terminal outcomes with the success-rate in the hub; legend lists the counts. */
@Composable
private fun OutcomeDonut(succeeded: Long, failed: Long, cancelled: Long, rate: Double?, progress: Float) {
    val green = MaterialTheme.schedulerColors.success
    val red = MaterialTheme.colorScheme.error
    val grey = MaterialTheme.colorScheme.onSurfaceVariant
    val track = MaterialTheme.colorScheme.surfaceContainerHigh
    val total = succeeded + failed + cancelled
    val segs = listOf(
        Triple("Succeeded", succeeded, green),
        Triple("Failed", failed, red),
        Triple("Cancelled", cancelled, grey),
    ).filter { it.second > 0 }

    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(24.dp)) {
        Box(modifier = Modifier.size(172.dp), contentAlignment = Alignment.Center) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val strokePx = 24.dp.toPx()
                val arcSize = Size(size.width - strokePx, size.height - strokePx)
                val topLeft = Offset(strokePx / 2f, strokePx / 2f)
                drawArc(track, 0f, 360f, false, topLeft, arcSize, style = Stroke(strokePx))
                if (total > 0) {
                    var start = -90f
                    segs.forEach { (_, v, c) ->
                        val sweep = (v.toFloat() / total) * 360f * progress
                        drawArc(c, start, sweep, false, topLeft, arcSize, style = Stroke(strokePx, cap = StrokeCap.Butt))
                        start += sweep
                    }
                }
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(rate.asPercent(), style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.onSurface)
                Text("success", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (segs.isEmpty()) {
                Text("No completed jobs", style = MaterialTheme.typography.bodySmall, color = grey)
            } else {
                segs.forEach { (label, v, c) -> LegendRow(c, label, v) }
            }
        }
    }
}

@Composable
private fun LegendRow(color: Color, label: String, value: Long) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(modifier = Modifier.size(10.dp).clip(CircleShape).background(color))
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.width(86.dp))
        Text(
            text = value.grouped(),
            style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

/** Horizontal bar chart of the live (non-terminal) states, each bar proportional to the busiest. */
@Composable
private fun PipelineBars(counts: List<Pair<JobState, Long>>, progress: Float) {
    val max = counts.maxOf { it.second }.coerceAtLeast(1)
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        counts.forEach { (state, count) ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = state.pretty(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.width(128.dp),
                )
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(22.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(MaterialTheme.colorScheme.surfaceContainerHigh),
                ) {
                    val frac = (count.toFloat() / max * progress).coerceIn(0f, 1f)
                    if (frac > 0f) {
                        Box(
                            modifier = Modifier
                                .fillMaxHeight()
                                .fillMaxWidth(frac)
                                .clip(RoundedCornerShape(6.dp))
                                .background(chartColor(state)),
                        )
                    }
                }
                Text(
                    text = count.grouped(),
                    style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                    color = MaterialTheme.colorScheme.onSurface,
                    textAlign = TextAlign.End,
                    modifier = Modifier.width(72.dp),
                )
            }
        }
    }
}

@Composable
private fun StatsSkeleton(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxWidth().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            repeat(5) { SkeletonBlock(Modifier.weight(1f).height(116.dp)) }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            SkeletonBlock(Modifier.weight(1f).height(260.dp))
            SkeletonBlock(Modifier.weight(1.5f).height(260.dp))
        }
    }
}

@Composable
private fun SkeletonBlock(modifier: Modifier) {
    Box(modifier = modifier.clip(MaterialTheme.shapes.medium).background(MaterialTheme.colorScheme.surfaceContainerHigh))
}

// Saturated, theme-aware fill per state — the pale chip containers in JobStateColors wash out as
// chart fills, so charts use the strong colour roles instead.
@Composable
private fun chartColor(state: JobState): Color = when (state) {
    JobState.SCHEDULED -> MaterialTheme.colorScheme.tertiary
    JobState.AWAITING_DEPS -> MaterialTheme.colorScheme.outline
    JobState.ENQUEUED -> MaterialTheme.colorScheme.primary
    JobState.PROCESSING -> MaterialTheme.colorScheme.secondary
    JobState.AWAITING_RETRY -> MaterialTheme.schedulerColors.warning
    JobState.SUCCEEDED -> MaterialTheme.schedulerColors.success
    JobState.FAILED -> MaterialTheme.colorScheme.error
    JobState.CANCELLED -> MaterialTheme.colorScheme.onSurfaceVariant
}

private fun JobState.pretty(): String = when (this) {
    JobState.SCHEDULED -> "Scheduled"
    JobState.AWAITING_DEPS -> "Awaiting deps"
    JobState.ENQUEUED -> "Enqueued"
    JobState.PROCESSING -> "Processing"
    JobState.AWAITING_RETRY -> "Awaiting retry"
    JobState.SUCCEEDED -> "Succeeded"
    JobState.FAILED -> "Failed"
    JobState.CANCELLED -> "Cancelled"
}

@Composable
private fun rateColor(rate: Double?): Color = when {
    rate == null -> MaterialTheme.colorScheme.onSurface
    rate >= 0.99 -> MaterialTheme.schedulerColors.success
    rate >= 0.90 -> MaterialTheme.schedulerColors.warning
    else -> MaterialTheme.colorScheme.error
}

/** `0.99198` → `"99.2%"`, null → `"—"`. One decimal, rounded. */
private fun Double?.asPercent(): String {
    if (this == null) return "—"
    val tenths = (this * 1000).roundToInt()
    return "${tenths / 10}.${tenths % 10}%"
}

/** `184500` → `"184,500"`. No Locale in wasm common, so group by hand. */
private fun Long.grouped(): String {
    val digits = toString()
    val sb = StringBuilder()
    for (i in digits.indices) {
        if (i > 0 && (digits.length - i) % 3 == 0) sb.append(',')
        sb.append(digits[i])
    }
    return sb.toString()
}
