package cs.trade.scheduler.dashboard.web.presentation.screens.jobdetail

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
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
import cs.trade.scheduler.dashboard.web.presentation.components.DependencyGraph
import cs.trade.scheduler.dashboard.web.presentation.components.PausedBadge
import cs.trade.scheduler.dashboard.web.presentation.components.StateChip
import cs.trade.scheduler.dashboard.web.presentation.components.timeAgo
import cs.trade.scheduler.shared.JobState
import cs.trade.scheduler.shared.dto.JobDetail
import cs.trade.scheduler.shared.dto.JobEventDto
import cs.trade.scheduler.shared.functionref.FunctionRefPayload
import cs.trade.scheduler.shared.functionref.FunctionRefPayloadFormatter

@OptIn(ExperimentalMaterial3Api::class)
@Composable
public fun JobDetailContent(component: JobDetailComponent) {
    val state by component.model.subscribeAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "Job ${state.jobId.take(8)}",
                        fontFamily = FontFamily.Monospace,
                    )
                },
                actions = {
                    OutlinedButton(onClick = component::onRefreshClicked) { Text("Refresh") }
                },
            )
        },
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            when {
                state.loading && state.detail == null -> CircularProgressIndicator(
                    modifier = Modifier.align(Alignment.Center),
                )
                state.error != null && state.detail == null -> Text(
                    text = state.error ?: "",
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.align(Alignment.Center).padding(16.dp),
                )
                state.detail != null -> JobDetailBody(
                    detail = state.detail!!,
                    paused = state.detail!!.job.payloadType in state.pausedTypes,
                    cancelling = state.cancelling,
                    onCancel = component::onCancelClicked,
                    cancelMessage = state.cancelResult?.name,
                    retrying = state.retrying,
                    onRetry = component::onRetryClicked,
                    onRetryOnce = component::onRetryOnceClicked,
                    retryMessage = state.retryResult?.name,
                    deleting = state.deleting,
                    confirmingDelete = state.confirmingDelete,
                    onDelete = component::onDeleteClicked,
                    onDeleteCancel = component::onDeleteConfirmCancelled,
                    deleteMessage = state.deleteResult?.name,
                    rerouting = state.rerouting,
                    rerouteFormOpen = state.rerouteFormOpen,
                    rerouteNode = state.rerouteNode,
                    rerouteTag = state.rerouteTag,
                    rerouteMessage = state.rerouteResult?.name,
                    onRerouteToggle = component::onRerouteFormToggled,
                    onRerouteNodeChange = component::onRerouteNodeChanged,
                    onRerouteTagChange = component::onRerouteTagChanged,
                    onRerouteSubmit = component::onRerouteSubmit,
                    onBack = component::onBackClicked,
                    onNavigate = component::onNeighbourClicked,
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun JobDetailBody(
    detail: JobDetail,
    paused: Boolean,
    cancelling: Boolean,
    onCancel: () -> Unit,
    cancelMessage: String?,
    retrying: Boolean,
    onRetry: () -> Unit,
    onRetryOnce: () -> Unit,
    retryMessage: String?,
    deleting: Boolean,
    confirmingDelete: Boolean,
    onDelete: () -> Unit,
    onDeleteCancel: () -> Unit,
    deleteMessage: String?,
    rerouting: Boolean,
    rerouteFormOpen: Boolean,
    rerouteNode: String,
    rerouteTag: String,
    rerouteMessage: String?,
    onRerouteToggle: () -> Unit,
    onRerouteNodeChange: (String) -> Unit,
    onRerouteTagChange: (String) -> Unit,
    onRerouteSubmit: () -> Unit,
    onBack: () -> Unit,
    onNavigate: (String) -> Unit,
) {
    val job = detail.job
    val payloadJson = detail.payloadJson
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            StateChip(job.state)
            Text(text = job.payloadType, style = MaterialTheme.typography.titleMedium)
            if (paused) PausedBadge()
        }

        FactGrid(
            "ID" to job.id,
            "Queue" to job.queue,
            "Priority" to job.priority.value.toString(),
            "Attempts" to "${job.attempts}/${job.maxAttempts}",
            "Created" to "${timeAgo(job.createdAt)} (${job.createdAt})",
            "Updated" to "${timeAgo(job.updatedAt)} (${job.updatedAt})",
            "Scheduled" to (job.scheduledAt?.toString() ?: "—"),
            "Duration" to (job.durationMs?.let { "${it}ms" } ?: "—"),
            "Locked by" to (job.lockedBy ?: "—"),
        )

        // Live progress bar: only when the handler has reported at least once. We keep
        // it visible after terminal too — the last reported value is useful audit info
        // (e.g. "made it to 0.9 before failing"). Updates arrive via WS firehose
        // (DESIGN.md 22.3); the bar mutates without a REST refresh.
        job.progress?.let { p ->
            ProgressBlock(progress = p, msg = job.progressMsg)
        }

        HorizontalDivider()

        Text("Payload", style = MaterialTheme.typography.titleMedium)
        // Function-ref jobs carry a structured payload — show it as `Mailer.send(123,
        // "welcome")` instead of dumping raw JSON. Falls back to raw JSON if the payload
        // doesn't parse (operator pasted a borked row, schema drift, etc.).
        val formatted = if (job.payloadType == FunctionRefPayload.FUNCTION_REF_PAYLOAD_TYPE) {
            FunctionRefPayloadFormatter.tryFormat(payloadJson)
        } else {
            null
        }
        if (formatted != null) {
            FunctionRefPayloadBlock(formatted = formatted, rawJson = payloadJson)
        } else {
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    text = payloadJson,
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    modifier = Modifier.padding(12.dp),
                )
            }
        }

        // Dependency graph — only when the job is actually part of a DAG (has at least one
        // edge). A standalone job gets a single-node graph from the server, which isn't worth
        // a diagram. Clicking any non-focal node navigates to its detail.
        val graph = detail.graph
        if (graph.edges.isNotEmpty()) {
            HorizontalDivider()
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text("Dependency graph", style = MaterialTheme.typography.titleMedium)
                Text(
                    text = "${graph.nodes.size} jobs",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (graph.truncated) {
                Text(
                    text = "Graph truncated to ${graph.nodes.size} nodes — some distant dependencies are not shown.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            DependencyGraph(graph = graph, focalId = job.id, onNavigate = onNavigate)
        }

        HorizontalDivider()

        Text("Timeline (${detail.events.size})", style = MaterialTheme.typography.titleMedium)
        if (detail.events.isEmpty()) {
            Text(
                text = "No events recorded yet",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            EventTimeline(detail.events)
        }

        HorizontalDivider()

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(onClick = onBack) { Text("Back") }
            if (!job.state.isTerminal) {
                Button(
                    onClick = onCancel,
                    enabled = !cancelling && !retrying,
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                ) {
                    Text(if (cancelling) "Cancelling…" else "Cancel job")
                }
            }
            // Manual retry is only meaningful on FAILED — SUCCEEDED / CANCELLED are
            // deliberate end-states. Backend enforces this too (returns NOT_FAILED), but
            // hiding the button keeps the UI honest.
            if (job.state == JobState.FAILED) {
                // "Retry": reset attempts to 0 → full fresh budget ("I fixed the cause").
                Button(
                    onClick = onRetry,
                    enabled = !retrying && !cancelling && !deleting,
                ) {
                    Text(if (retrying) "Retrying…" else "Retry")
                }
                // "Retry +1": exactly one more attempt without resetting the budget — for a
                // suspected-transient failure where a full fresh budget would be overkill
                // (DESIGN.md 9.5). Secondary styling so the primary "Retry" stays dominant.
                OutlinedButton(
                    onClick = onRetryOnce,
                    enabled = !retrying && !cancelling && !deleting,
                ) {
                    Text(if (retrying) "Retrying…" else "Retry +1")
                }
            }
            // Delete is terminal-only — backend rejects others with NOT_TERMINAL but UI
            // mirrors. Two-step inline confirm: first click arms (label flips + a Cancel
            // button appears), second commits. Pop back to list on success.
            if (job.state.isTerminal) {
                Button(
                    onClick = onDelete,
                    enabled = !deleting,
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                ) {
                    val label = when {
                        deleting -> "Deleting…"
                        confirmingDelete -> "Click again to confirm"
                        else -> "Delete"
                    }
                    Text(label)
                }
                if (confirmingDelete && !deleting) {
                    OutlinedButton(onClick = onDeleteCancel) { Text("Cancel delete") }
                }
            }
            // Reroute — non-terminal only. Useful when a job sits in q.node.X with X
            // offline. Toggle expands an inline form; backend accepts node OR tag.
            if (!job.state.isTerminal) {
                OutlinedButton(onClick = onRerouteToggle, enabled = !rerouting) {
                    Text(if (rerouteFormOpen) "Cancel reroute" else "Re-route")
                }
            }
        }

        if (rerouteFormOpen) {
            Surface(color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "Re-route — fill node OR tag (or both empty to clear targets):",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        OutlinedTextField(
                            value = rerouteNode,
                            onValueChange = onRerouteNodeChange,
                            label = { Text("Target node") },
                            singleLine = true,
                            enabled = !rerouting,
                            modifier = Modifier.width(220.dp),
                            textStyle = MaterialTheme.typography.bodySmall,
                        )
                        OutlinedTextField(
                            value = rerouteTag,
                            onValueChange = onRerouteTagChange,
                            label = { Text("Target tag") },
                            singleLine = true,
                            enabled = !rerouting,
                            modifier = Modifier.width(220.dp),
                            textStyle = MaterialTheme.typography.bodySmall,
                        )
                        Button(onClick = onRerouteSubmit, enabled = !rerouting) {
                            Text(if (rerouting) "Applying…" else "Apply")
                        }
                    }
                }
            }
        }

        cancelMessage?.let {
            Text(
                text = "Cancel result: $it",
                color = MaterialTheme.colorScheme.primary,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        retryMessage?.let {
            Text(
                text = "Retry result: $it",
                color = MaterialTheme.colorScheme.primary,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        deleteMessage?.let {
            Text(
                text = "Delete result: $it",
                color = MaterialTheme.colorScheme.primary,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        rerouteMessage?.let {
            Text(
                text = "Re-route result: $it",
                color = MaterialTheme.colorScheme.primary,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

@Composable
private fun ProgressBlock(progress: Float, msg: String?) {
    val pct = (progress.coerceIn(0f, 1f) * 100).toInt()
    Column(verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Progress", style = MaterialTheme.typography.titleSmall)
            Text("$pct%", style = MaterialTheme.typography.bodyMedium)
        }
        LinearProgressIndicator(
            progress = { progress.coerceIn(0f, 1f) },
            modifier = Modifier.fillMaxWidth(),
        )
        if (!msg.isNullOrBlank()) {
            Text(
                text = msg,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun FunctionRefPayloadBlock(
    formatted: FunctionRefPayloadFormatter.Formatted,
    rawJson: String,
) {
    var showRaw by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        // Headline: the Kotlin-call rendering. Monospace so `Mailer.send(123, "welcome")`
        // reads like code, not paragraph text.
        Surface(
            color = MaterialTheme.colorScheme.surfaceVariant,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                text = formatted.oneLine,
                style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                modifier = Modifier.padding(12.dp),
            )
        }
        // Detailed breakdown — typed args with their declared parameter type. Hidden when
        // there are no args (zero-arg call already reads fully from the oneLine).
        if (formatted.args.isNotEmpty()) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                formatted.args.forEachIndexed { idx, arg ->
                    Text(
                        text = "  arg[$idx] (${arg.type}) = ${arg.valueRendered}",
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        // Qualifier hint when present — tells the operator which Koin binding will be used.
        if (formatted.targetQualifier != null) {
            Text(
                text = "Koin qualifier: \"${formatted.targetQualifier}\"",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        // Full receiver FQN — useful for ops debugging when the simple name is ambiguous.
        Text(
            text = "Receiver: ${formatted.receiverFqn}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        // Expandable raw JSON — for the rare case where the formatter dropped some detail
        // (composite arg JSON shortened, escape handling) and the operator wants the
        // ground truth. One click, one fewer click than copying out of a DB query.
        Text(
            text = if (showRaw) "Hide raw JSON" else "Show raw JSON",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.clickable { showRaw = !showRaw },
        )
        if (showRaw) {
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    text = rawJson,
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    modifier = Modifier.padding(12.dp),
                )
            }
        }
    }
}

@Composable
private fun EventTimeline(events: List<JobEventDto>) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        events.forEach { ev ->
            EventRow(ev)
        }
    }
}

@Composable
private fun EventRow(ev: JobEventDto) {
    // Per-event expansion state. Keyed by event id so re-composes preserve the toggle.
    var stackExpanded by remember(ev.id) { mutableStateOf(false) }

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = timeAgo(ev.occurredAt),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(80.dp),
        )
        Text(
            text = ev.eventType,
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.width(140.dp),
        )
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.width(260.dp),
        ) {
            ev.prevState?.let { StateChip(it) }
            if (ev.prevState != null && ev.newState != null) {
                Text(
                    "->",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            ev.newState?.let { StateChip(it) }
        }
        ev.actor?.let {
            Text(
                text = "by $it",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
    // Error message + collapsible stack trace (aligned under the row content, indented past
    // the timestamp column so eye flow is "what failed" -> "the trace if I expand it").
    if (ev.errorMsg != null || ev.errorStack != null) {
        Column(
            modifier = Modifier.padding(start = 92.dp).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            ev.errorMsg?.let { msg ->
                Text(
                    text = msg,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            ev.errorStack?.let { stack ->
                val toggleLabel = if (stackExpanded) "▼ Hide stack trace" else "▶ Show stack trace"
                Text(
                    text = toggleLabel,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .clickable { stackExpanded = !stackExpanded }
                        .padding(vertical = 2.dp),
                )
                if (stackExpanded) {
                    Surface(
                        color = MaterialTheme.colorScheme.errorContainer,
                        shape = RoundedCornerShape(4.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            text = stack,
                            style = MaterialTheme.typography.bodySmall.copy(
                                fontFamily = FontFamily.Monospace,
                            ),
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            modifier = Modifier.padding(8.dp),
                        )
                    }
                    Spacer(Modifier.padding(top = 2.dp))
                }
            }
        }
    }
}

@Composable
private fun FactGrid(vararg facts: Pair<String, String>) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        facts.forEach { (label, value) ->
            Row(verticalAlignment = Alignment.Top) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.width(120.dp),
                )
                Text(
                    text = value,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }
}

