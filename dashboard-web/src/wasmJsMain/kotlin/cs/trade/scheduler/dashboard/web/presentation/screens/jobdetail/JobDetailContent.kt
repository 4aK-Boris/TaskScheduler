package cs.trade.scheduler.dashboard.web.presentation.screens.jobdetail

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.arkivanov.decompose.extensions.compose.subscribeAsState
import cs.trade.scheduler.core.frontend.theme.schedulerColors
import cs.trade.scheduler.dashboard.web.presentation.components.CopyableText
import cs.trade.scheduler.dashboard.web.presentation.components.DependencyGraph
import cs.trade.scheduler.dashboard.web.presentation.components.PausedBadge
import cs.trade.scheduler.dashboard.web.presentation.components.SettingsMenu
import cs.trade.scheduler.dashboard.web.presentation.components.SkeletonBar
import cs.trade.scheduler.dashboard.web.presentation.components.StateChip
import cs.trade.scheduler.dashboard.web.presentation.components.formatDateTime
import cs.trade.scheduler.dashboard.web.presentation.components.timeAgo
import cs.trade.scheduler.shared.JobState
import cs.trade.scheduler.shared.dto.JobDetail
import cs.trade.scheduler.shared.dto.JobEventDto
import cs.trade.scheduler.shared.dto.JobView
import cs.trade.scheduler.shared.functionref.FunctionRefPayload
import cs.trade.scheduler.shared.functionref.FunctionRefPayloadFormatter
import kotlinx.coroutines.delay
import kotlin.time.Instant

// Below this the two-column layout (content + action sidebar) collapses to a single stack.
private val TWO_COLUMN_MIN = 980.dp

@Composable
public fun JobDetailContent(component: JobDetailComponent) {
    val state by component.model.subscribeAsState()
    Column(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        JobHeader(
            jobId = state.jobId,
            detail = state.detail,
            paused = state.detail?.let { it.job.payloadType in state.pausedTypes } ?: false,
            autoRefreshSeconds = state.autoRefreshSeconds,
            onAutoRefreshChanged = component::onAutoRefreshChanged,
            timeAbsolute = state.timeAbsolute,
            onTimeModeChanged = component::onTimeModeChanged,
            onBack = component::onBackClicked,
            onRefresh = component::onRefreshClicked,
        )
        Box(modifier = Modifier.fillMaxSize()) {
            when {
                state.loading && state.detail == null ->
                    JobDetailSkeleton(modifier = Modifier.align(Alignment.TopStart))
                state.error != null && state.detail == null -> Text(
                    text = state.error ?: "",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.align(Alignment.Center).padding(24.dp),
                )
                state.detail != null -> JobDetailBody(component, state, state.detail!!)
            }
        }
    }
}

@Composable
private fun JobHeader(
    jobId: String,
    detail: JobDetail?,
    paused: Boolean,
    autoRefreshSeconds: Int?,
    onAutoRefreshChanged: (Int?) -> Unit,
    timeAbsolute: Boolean,
    onTimeModeChanged: (Boolean) -> Unit,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, top = 18.dp, bottom = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            detail?.let { StateChip(it.job.state) }
            Text(
                text = "Job ${jobId.take(8)}",
                style = MaterialTheme.typography.headlineSmall.copy(fontFamily = FontFamily.Monospace),
                color = MaterialTheme.colorScheme.onBackground,
            )
            detail?.let {
                Text(
                    text = it.job.payloadType.substringAfterLast('.'),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (paused) PausedBadge()
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            SettingsMenu(
                autoRefreshSeconds = autoRefreshSeconds,
                onAutoRefreshChanged = onAutoRefreshChanged,
                timeSectionLabel = "Timestamps",
                relativeLabel = "Relative (3m ago)",
                timeAbsolute = timeAbsolute,
                onTimeModeChanged = onTimeModeChanged,
            )
            OutlinedButton(onClick = onBack, shape = MaterialTheme.shapes.small) { Text("Back") }
            OutlinedButton(onClick = onRefresh, shape = MaterialTheme.shapes.small) { Text("Refresh") }
        }
    }
}

@Composable
private fun JobDetailBody(component: JobDetailComponent, m: JobDetailComponent.Model, detail: JobDetail) {
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())
            .padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        BoxWithConstraints {
            val wide = maxWidth >= TWO_COLUMN_MIN
            if (wide) {
                Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Column(modifier = Modifier.weight(1.7f), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        OverviewPanel(detail.job, m.timeAbsolute)
                        PayloadPanel(detail.job, detail.payloadJson)
                        TimelinePanel(detail.events, m.timeAbsolute)
                    }
                    Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        ActionsPanel(component, m, detail.job)
                    }
                }
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    ActionsPanel(component, m, detail.job)
                    OverviewPanel(detail.job, m.timeAbsolute)
                    PayloadPanel(detail.job, detail.payloadJson)
                    TimelinePanel(detail.events, m.timeAbsolute)
                }
            }
        }
        // Dependency graph spans the full width — it pans horizontally and can get wide.
        if (detail.graph.edges.isNotEmpty()) {
            GraphPanel(detail, focalId = detail.job.id, onNavigate = component::onNeighbourClicked)
        }
    }
}

// ---- reusable card -------------------------------------------------------------------------

@Composable
private fun Panel(title: String, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surface,
        shape = MaterialTheme.shapes.medium,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            SectionLabel(title)
            content()
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text.uppercase(),
        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Medium, letterSpacing = 0.6.sp),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

// ---- overview ------------------------------------------------------------------------------

@Composable
private fun OverviewPanel(job: JobView, timeAbsolute: Boolean) {
    Panel("Overview") {
        FactCell(Modifier.fillMaxWidth(), "Job ID", job.id, mono = true, copyable = true)
        FactCell(Modifier.fillMaxWidth(), "Payload type", job.payloadType, mono = true, copyable = true)
        FactRow {
            FactCell(Modifier.weight(1f), "Queue", job.queue)
            FactCell(Modifier.weight(1f), "Priority", job.priority.value.toString())
        }
        FactRow {
            FactCell(Modifier.weight(1f), "Attempts", "${job.attempts} / ${job.maxAttempts}")
            FactCell(Modifier.weight(1f), "Duration", job.durationMs?.let { "$it ms" } ?: "—")
        }
        FactRow {
            FactCell(Modifier.weight(1f), "Locked by", job.lockedBy ?: "—", mono = job.lockedBy != null)
            FactCell(Modifier.weight(1f), "Scheduled", job.scheduledAt?.let { fmtTime(it, timeAbsolute) } ?: "—")
        }
        FactRow {
            FactCell(Modifier.weight(1f), "Created", fmtTime(job.createdAt, timeAbsolute))
            FactCell(Modifier.weight(1f), "Updated", fmtTime(job.updatedAt, timeAbsolute))
        }
        job.progress?.let { p ->
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            ProgressBlock(p, job.progressMsg, job.progressSucceeded, job.progressFailed, job.progressTotal)
        }
    }
}

@Composable
private fun FactRow(content: @Composable RowScope.() -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(24.dp), content = content)
}

@Composable
private fun FactCell(modifier: Modifier, label: String, value: String, mono: Boolean = false, copyable: Boolean = false) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(3.dp)) {
        SectionLabel(label)
        val valueStyle = MaterialTheme.typography.bodyMedium.let {
            if (mono) it.copy(fontFamily = FontFamily.Monospace) else it
        }
        if (copyable) {
            CopyableText(
                text = value,
                style = valueStyle,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.fillMaxWidth(),
            )
        } else {
            Text(text = value, style = valueStyle, color = MaterialTheme.colorScheme.onSurface)
        }
    }
}

@Composable
private fun ProgressBlock(progress: Float, msg: String?, succeeded: Long?, failed: Long?, total: Long?) {
    val pct = (progress.coerceIn(0f, 1f) * 100).toInt()
    val success = MaterialTheme.schedulerColors.success
    val fail = MaterialTheme.colorScheme.error
    Column(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SectionLabel("Progress")
            Text("$pct%", style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace))
        }
        Box(
            modifier = Modifier.fillMaxWidth().height(10.dp).clip(MaterialTheme.shapes.extraSmall)
                .background(MaterialTheme.colorScheme.surfaceContainerHigh),
        ) {
            // Counting bar (JobContext.progressBar): green succeeded + red failed segments.
            // Falls back to a single cobalt fill when the handler used plain updateProgress.
            if (succeeded != null && failed != null && total != null && total > 0L) {
                val succeededFrac = (succeeded.toFloat() / total).coerceIn(0f, 1f)
                val failedFrac = (failed.toFloat() / total).coerceIn(0f, 1f - succeededFrac)
                Row(Modifier.fillMaxSize()) {
                    if (succeededFrac > 0f) Box(Modifier.fillMaxHeight().weight(succeededFrac).background(success))
                    if (failedFrac > 0f) Box(Modifier.fillMaxHeight().weight(failedFrac).background(fail))
                    val rest = (1f - succeededFrac - failedFrac).coerceIn(0f, 1f)
                    if (rest > 0f) Box(Modifier.fillMaxHeight().weight(rest))
                }
            } else {
                Box(
                    Modifier.fillMaxHeight().fillMaxWidth(progress.coerceIn(0f, 1f))
                        .background(MaterialTheme.colorScheme.primary),
                )
            }
        }
        if (succeeded != null && failed != null && total != null && total > 0L) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("✓ $succeeded", color = success, style = MaterialTheme.typography.bodySmall)
                Text("✗ $failed", color = fail, style = MaterialTheme.typography.bodySmall)
                Text("/ $total", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
            }
        }
        if (!msg.isNullOrBlank()) {
            Text(msg, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

// ---- payload -------------------------------------------------------------------------------

@Composable
private fun PayloadPanel(job: JobView, payloadJson: String) {
    Panel("Payload") {
        // Function-ref jobs render as `Mailer.send(123, "welcome")`; everything else as raw JSON.
        val formatted = if (job.payloadType == FunctionRefPayload.FUNCTION_REF_PAYLOAD_TYPE) {
            FunctionRefPayloadFormatter.tryFormat(payloadJson)
        } else {
            null
        }
        if (formatted != null) FunctionRefPayloadBlock(formatted, payloadJson) else CodeBlock(payloadJson)
    }
}

@Composable
private fun CodeBlock(text: String) {
    Box(
        modifier = Modifier.fillMaxWidth().clip(MaterialTheme.shapes.small)
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, MaterialTheme.shapes.small)
            .padding(12.dp),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Composable
private fun FunctionRefPayloadBlock(formatted: FunctionRefPayloadFormatter.Formatted, rawJson: String) {
    var showRaw by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        CodeBlock(formatted.oneLine)
        if (formatted.args.isNotEmpty()) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                formatted.args.forEachIndexed { idx, arg ->
                    Text(
                        text = "arg[$idx] (${arg.type}) = ${arg.valueRendered}",
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        formatted.targetQualifier?.let {
            Text("Koin qualifier: \"$it\"", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text(
            "Receiver: ${formatted.receiverFqn}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = if (showRaw) "Hide raw JSON" else "Show raw JSON",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.clickable { showRaw = !showRaw },
        )
        Box(modifier = Modifier.animateContentSize()) {
            if (showRaw) CodeBlock(rawJson)
        }
    }
}

// ---- dependency graph ----------------------------------------------------------------------

@Composable
private fun GraphPanel(detail: JobDetail, focalId: String, onNavigate: (String) -> Unit) {
    val graph = detail.graph
    Panel("Dependency graph") {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = "${graph.nodes.size} jobs",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (graph.truncated) {
                Text(
                    text = "· truncated — some distant dependencies are not shown",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
        DependencyGraph(graph = graph, focalId = focalId, onNavigate = onNavigate)
    }
}

// ---- timeline ------------------------------------------------------------------------------

@Composable
private fun TimelinePanel(events: List<JobEventDto>, timeAbsolute: Boolean) {
    Panel("Timeline · ${events.size}") {
        if (events.isEmpty()) {
            Text(
                text = "No events recorded yet",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            Column {
                events.forEachIndexed { i, ev ->
                    TimelineRow(ev, isFirst = i == 0, isLast = i == events.lastIndex, timeAbsolute = timeAbsolute)
                }
            }
        }
    }
}

@Composable
private fun TimelineRow(ev: JobEventDto, isFirst: Boolean, isLast: Boolean, timeAbsolute: Boolean) {
    var stackExpanded by remember(ev.id) { mutableStateOf(false) }
    val dotColor = eventColor(ev.eventType)
    val rail = MaterialTheme.colorScheme.outlineVariant

    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        // Rail gutter: a continuous vertical line with a dot per event (clipped at the ends).
        Canvas(modifier = Modifier.width(16.dp).fillMaxHeight()) {
            val cx = size.width / 2f
            val dotY = 16.dp.toPx()
            val r = 5.dp.toPx()
            val stroke = 2.dp.toPx()
            if (!isFirst) drawLine(rail, Offset(cx, 0f), Offset(cx, dotY), strokeWidth = stroke, cap = StrokeCap.Round)
            if (!isLast) drawLine(rail, Offset(cx, dotY), Offset(cx, size.height), strokeWidth = stroke, cap = StrokeCap.Round)
            drawCircle(dotColor, radius = r, center = Offset(cx, dotY))
        }
        Column(
            modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = ev.eventType,
                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Medium),
                    color = MaterialTheme.colorScheme.onSurface,
                )
                ev.prevState?.let { StateChip(it) }
                if (ev.prevState != null && ev.newState != null) {
                    Text("→", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                ev.newState?.let { StateChip(it) }
                Spacer(Modifier.weight(1f))
                Text(
                    text = fmtTime(ev.occurredAt, timeAbsolute),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            ev.actor?.let {
                Text("by $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            ev.errorMsg?.let { msg ->
                Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = msg,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.weight(1f),
                    )
                    CopyButton(value = msg, label = "Copy")
                }
            }
            ev.errorStack?.let { stack ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier.clickable { stackExpanded = !stackExpanded },
                    ) {
                        DisclosureChevron(expanded = stackExpanded, color = MaterialTheme.colorScheme.primary)
                        Text(
                            text = if (stackExpanded) "Hide stack trace" else "Show stack trace",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                    CopyButton(value = stack, label = "Copy trace")
                }
                Box(modifier = Modifier.animateContentSize()) {
                    if (stackExpanded) {
                        Surface(
                            color = MaterialTheme.colorScheme.errorContainer,
                            shape = MaterialTheme.shapes.small,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(
                                text = stack,
                                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                                color = MaterialTheme.colorScheme.onErrorContainer,
                                modifier = Modifier.padding(10.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

// One timestamp, not two — honours the header's relative/absolute toggle.
private fun fmtTime(instant: Instant, absolute: Boolean): String =
    if (absolute) formatDateTime(instant) else timeAgo(instant)

/** Disclosure chevron drawn on Canvas — points right when collapsed, down when expanded. Replaces
 *  the ▶/▼ glyphs, which skiko rendered as broken boxes. */
@Composable
private fun DisclosureChevron(expanded: Boolean, color: Color) {
    Canvas(modifier = Modifier.size(10.dp)) {
        val w = size.width
        val h = size.height
        val sw = w * 0.16f
        if (expanded) {
            drawLine(color, Offset(w * 0.18f, h * 0.34f), Offset(w * 0.50f, h * 0.70f), sw, cap = StrokeCap.Round)
            drawLine(color, Offset(w * 0.50f, h * 0.70f), Offset(w * 0.82f, h * 0.34f), sw, cap = StrokeCap.Round)
        } else {
            drawLine(color, Offset(w * 0.34f, h * 0.18f), Offset(w * 0.70f, h * 0.50f), sw, cap = StrokeCap.Round)
            drawLine(color, Offset(w * 0.70f, h * 0.50f), Offset(w * 0.34f, h * 0.82f), sw, cap = StrokeCap.Round)
        }
    }
}

/** Inline link-style button that copies [value] to the clipboard, flashing "Copied ✓" briefly. */
// LocalClipboardManager is deprecated for the suspend LocalClipboard, but that needs a
// platform-specific ClipEntry which is awkward on wasm; the synchronous setText works fine here.
@Suppress("DEPRECATION")
@Composable
private fun CopyButton(value: String, label: String) {
    val clipboard = LocalClipboardManager.current
    var copied by remember { mutableStateOf(false) }
    LaunchedEffect(copied) {
        if (copied) {
            delay(1400)
            copied = false
        }
    }
    Text(
        text = if (copied) "Copied ✓" else label,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.primary,
        maxLines = 1,
        softWrap = false,
        modifier = Modifier.clickable {
            clipboard.setText(AnnotatedString(value))
            copied = true
        },
    )
}

@Composable
private fun eventColor(type: String): Color = type.uppercase().let { t ->
    when {
        "FAIL" in t || "TIMEOUT" in t || "ERROR" in t -> MaterialTheme.colorScheme.error
        "SUCC" in t -> MaterialTheme.schedulerColors.success
        "CANCEL" in t -> MaterialTheme.colorScheme.onSurfaceVariant
        "RETRY" in t -> MaterialTheme.schedulerColors.warning
        else -> MaterialTheme.colorScheme.primary
    }
}

// ---- actions sidebar -----------------------------------------------------------------------

@Composable
private fun ActionsPanel(component: JobDetailComponent, m: JobDetailComponent.Model, job: JobView) {
    Panel("Actions") {
        val busy = m.cancelling || m.retrying || m.deleting || m.rerouting || m.rerunning
        val errorColors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)

        if (!job.state.isTerminal) {
            Button(
                onClick = component::onCancelClicked,
                enabled = !busy,
                shape = MaterialTheme.shapes.small,
                colors = errorColors,
                modifier = Modifier.fillMaxWidth(),
            ) { Text(if (m.cancelling) "Cancelling…" else "Cancel job") }
        }

        // Manual retry is FAILED-only — SUCCEEDED / CANCELLED are deliberate end-states.
        if (job.state == JobState.FAILED) {
            Button(
                onClick = component::onRetryClicked,
                enabled = !busy,
                shape = MaterialTheme.shapes.small,
                modifier = Modifier.fillMaxWidth(),
            ) { Text(if (m.retrying) "Retrying…" else "Retry (fresh budget)") }
            OutlinedButton(
                onClick = component::onRetryOnceClicked,
                enabled = !busy,
                shape = MaterialTheme.shapes.small,
                modifier = Modifier.fillMaxWidth(),
            ) { Text(if (m.retrying) "Retrying…" else "Retry +1") }
        }

        // Re-route — non-terminal only. Toggle reveals an inline node/tag form.
        if (!job.state.isTerminal) {
            OutlinedButton(
                onClick = component::onRerouteFormToggled,
                enabled = !m.rerouting,
                shape = MaterialTheme.shapes.small,
                modifier = Modifier.fillMaxWidth(),
            ) { Text(if (m.rerouteFormOpen) "Cancel re-route" else "Re-route") }
            Box(modifier = Modifier.animateContentSize()) {
                if (m.rerouteFormOpen) RerouteForm(component, m)
            }
        }

        // Re-run — clone this job into a fresh ENQUEUED one. Terminal-only in the UI (re-running
        // an in-flight job would just create a duplicate); the backend allows any state. On
        // success the component navigates to the new job, so there's no result banner here.
        if (job.state.isTerminal) {
            OutlinedButton(
                onClick = component::onRerunClicked,
                enabled = !busy,
                shape = MaterialTheme.shapes.small,
                modifier = Modifier.fillMaxWidth(),
            ) { Text(if (m.rerunning) "Re-running…" else "Re-run") }
        }

        // Delete — terminal-only, two-step inline confirm.
        if (job.state.isTerminal) {
            Button(
                onClick = component::onDeleteClicked,
                enabled = !m.deleting,
                shape = MaterialTheme.shapes.small,
                colors = errorColors,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    when {
                        m.deleting -> "Deleting…"
                        m.confirmingDelete -> "Click again to confirm"
                        else -> "Delete"
                    },
                )
            }
            if (m.confirmingDelete && !m.deleting) {
                OutlinedButton(
                    onClick = component::onDeleteConfirmCancelled,
                    shape = MaterialTheme.shapes.small,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Cancel delete") }
            }
        }

        m.cancelResult?.let { ResultBanner("Cancel", it.name) }
        m.retryResult?.let { ResultBanner("Retry", it.name) }
        m.deleteResult?.let { ResultBanner("Delete", it.name) }
        m.rerouteResult?.let { ResultBanner("Re-route", it.name) }
        m.error?.let { ResultBanner("Error", it, isError = true) }
    }
}

@Composable
private fun RerouteForm(component: JobDetailComponent, m: JobDetailComponent.Model) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            "Fill node OR tag (both empty → default queue):",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedTextField(
            value = m.rerouteNode,
            onValueChange = component::onRerouteNodeChanged,
            label = { Text("Target node") },
            singleLine = true,
            enabled = !m.rerouting,
            shape = MaterialTheme.shapes.small,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = m.rerouteTag,
            onValueChange = component::onRerouteTagChanged,
            label = { Text("Target tag") },
            singleLine = true,
            enabled = !m.rerouting,
            shape = MaterialTheme.shapes.small,
            modifier = Modifier.fillMaxWidth(),
        )
        Button(
            onClick = component::onRerouteSubmit,
            enabled = !m.rerouting,
            shape = MaterialTheme.shapes.small,
            modifier = Modifier.fillMaxWidth(),
        ) { Text(if (m.rerouting) "Applying…" else "Apply re-route") }
    }
}

@Composable
private fun ResultBanner(label: String, value: String, isError: Boolean = false) {
    val fg = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
    Surface(
        color = if (isError) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.surfaceContainerLow,
        shape = MaterialTheme.shapes.small,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            text = "$label: $value",
            style = MaterialTheme.typography.bodySmall,
            color = if (isError) MaterialTheme.colorScheme.onErrorContainer else fg,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
        )
    }
}

// ---- skeleton ------------------------------------------------------------------------------

@Composable
private fun JobDetailSkeleton(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxWidth().padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        repeat(6) {
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                SkeletonBar(120.dp)
                SkeletonBar(220.dp)
                SkeletonBar(90.dp)
            }
        }
    }
}
