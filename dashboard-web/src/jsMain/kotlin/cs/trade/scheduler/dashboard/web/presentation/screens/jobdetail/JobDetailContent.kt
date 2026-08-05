package cs.trade.scheduler.dashboard.web.presentation.screens.jobdetail

import cs.trade.scheduler.core.frontend.react.useValue
import cs.trade.scheduler.core.frontend.theme.SchedulerColors
import cs.trade.scheduler.core.frontend.theme.SchedulerRadius
import cs.trade.scheduler.core.frontend.theme.SchedulerText
import cs.trade.scheduler.core.frontend.ui.Button
import cs.trade.scheduler.core.frontend.ui.ButtonSize
import cs.trade.scheduler.core.frontend.ui.ButtonVariant
import cs.trade.scheduler.core.frontend.ui.ErrorBanner
import cs.trade.scheduler.core.frontend.ui.Panel
import cs.trade.scheduler.core.frontend.ui.TextInput
import cs.trade.scheduler.core.frontend.ui.flexColumn
import cs.trade.scheduler.core.frontend.ui.flexRow
import cs.trade.scheduler.core.frontend.ui.hairline
import cs.trade.scheduler.dashboard.web.presentation.components.CopyableText
import cs.trade.scheduler.dashboard.web.presentation.components.DependencyGraph
import cs.trade.scheduler.dashboard.web.presentation.components.PageHeader
import cs.trade.scheduler.dashboard.web.presentation.components.PausedBadge
import cs.trade.scheduler.dashboard.web.presentation.components.SettingsMenu
import cs.trade.scheduler.dashboard.web.presentation.components.SkeletonRows
import cs.trade.scheduler.dashboard.web.presentation.components.StateChip
import cs.trade.scheduler.dashboard.web.presentation.components.formatDateTime
import cs.trade.scheduler.dashboard.web.presentation.components.timeAgo
import cs.trade.scheduler.shared.JobState
import cs.trade.scheduler.shared.dto.JobDetail
import cs.trade.scheduler.shared.dto.JobEventDto
import cs.trade.scheduler.shared.dto.JobView
import cs.trade.scheduler.shared.functionref.FunctionRefPayload
import cs.trade.scheduler.shared.functionref.FunctionRefPayloadFormatter
import emotion.react.css
import kotlinx.coroutines.delay
import react.FC
import react.Key
import react.Props
import react.PropsWithChildren
import react.dom.html.ReactHTML.div
import react.dom.html.ReactHTML.pre
import react.dom.html.ReactHTML.span
import react.useEffect
import react.useState
import web.cssom.AlignItems
import web.cssom.Auto
import web.cssom.Color
import web.cssom.Cursor
import web.cssom.FlexWrap
import web.cssom.JustifyContent
import web.cssom.MediaQuery
import web.cssom.Padding
import web.cssom.TextTransform
import web.cssom.WhiteSpace
import web.cssom.integer
import web.cssom.number
import web.cssom.pct
import web.cssom.px
import web.navigator.navigator
import kotlin.time.Instant

/**
 * Everything known about one job: its facts, payload, event timeline, dependency graph, and the
 * operator actions available in its current state.
 *
 * Two columns on a wide viewport (content + action sidebar), one stack below 980px — with the
 * actions moved to the TOP of the stack, since on a narrow screen they'd otherwise sit below a
 * long timeline.
 */
external interface JobDetailContentProps : Props {
    var component: JobDetailComponent
}

public val JobDetailContent: FC<JobDetailContentProps> = FC { props ->
    val component = props.component
    val model = useValue(component.model)
    val detail = model.detail

    div {
        css {
            flexColumn()
            height = 100.pct
            minHeight = 0.px
        }

        PageHeader {
            title = "Job ${model.jobId.take(8)}"

            SettingsMenu {
                autoRefreshSeconds = model.autoRefreshSeconds
                onAutoRefreshChanged = component::onAutoRefreshChanged
                timeSectionLabel = "Timestamps"
                relativeLabel = "Relative (3m ago)"
                timeAbsolute = model.timeAbsolute
                onTimeModeChanged = component::onTimeModeChanged
            }
            Button {
                onClick = component::onBackClicked
                +"Back"
            }
            Button {
                onClick = component::onRefreshClicked
                +"Refresh"
            }
        }

        // Identity strip under the title — state, payload type and the paused badge.
        if (detail != null) {
            div {
                css {
                    flexRow(gap = 12.px)
                    padding = Padding(0.px, 24.px, 12.px)
                }
                StateChip { state = detail.job.state }
                span {
                    css { color = SchedulerColors.onSurfaceVariant }
                    +detail.job.payloadType.substringAfterLast('.')
                }
                if (detail.job.payloadType in model.pausedTypes) {
                    PausedBadge()
                }
            }
        }

        div {
            css {
                flexGrow = number(1.0)
                minHeight = 0.px
                overflowY = Auto.auto
                padding = Padding(0.px, 16.px, 16.px)
            }
            when {
                model.loading && detail == null -> SkeletonRows {
                    rows = 6
                    widths = listOf(120.px, 220.px, 90.px)
                }

                model.error != null && detail == null -> ErrorBanner {
                    message = model.error ?: ""
                    onRetry = component::onRefreshClicked
                }

                detail != null -> JobDetailBody {
                    this.component = component
                    this.model = model
                    this.detail = detail
                }
            }
        }
    }
}

private external interface JobDetailBodyProps : Props {
    var component: JobDetailComponent
    var model: JobDetailComponent.Model
    var detail: JobDetail
}

private val JobDetailBody: FC<JobDetailBodyProps> = FC { props ->
    val detail = props.detail
    val model = props.model

    div {
        css { flexColumn(gap = 16.px) }

        div {
            css {
                flexRow(gap = 16.px, align = AlignItems.flexStart)
                flexWrap = FlexWrap.wrap
            }

            div {
                css {
                    flexColumn(gap = 16.px)
                    flexGrow = number(1.7)
                    flexBasis = 560.px
                    minWidth = 0.px
                }
                OverviewPanel {
                    job = detail.job
                    timeAbsolute = model.timeAbsolute
                }
                PayloadPanel {
                    job = detail.job
                    payloadJson = detail.payloadJson
                }
                TimelinePanel {
                    events = detail.events
                    timeAbsolute = model.timeAbsolute
                }
            }

            div {
                css {
                    flexColumn(gap = 16.px)
                    flexGrow = number(1.0)
                    flexBasis = 300.px
                    minWidth = 0.px
                    // Once the columns wrap, the sidebar leads — on a narrow screen the actions
                    // must not sit below a long timeline.
                    `@media`(MediaQuery("(max-width: 979px)")) {
                        order = integer(-1)
                    }
                }
                ActionsPanel {
                    component = props.component
                    this.model = model
                    job = detail.job
                }
            }
        }

        // The dependency graph spans the full width — it pans horizontally and can get wide.
        if (detail.graph.edges.isNotEmpty()) {
            SectionPanel {
                title = "Dependency graph"
                div {
                    css { flexRow(gap = 8.px) }
                    span {
                        css { color = SchedulerColors.onSurfaceVariant }
                        +"${detail.graph.nodes.size} jobs"
                    }
                    if (detail.graph.truncated) {
                        span {
                            css { color = SchedulerColors.error }
                            +"· truncated — some distant dependencies are not shown"
                        }
                    }
                }
                DependencyGraph {
                    graph = detail.graph
                    focalId = detail.job.id
                    onNavigate = props.component::onNeighbourClicked
                }
            }
        }
    }
}

// ---- reusable card ---------------------------------------------------------------------------

private external interface SectionPanelProps : PropsWithChildren {
    var title: String
}

private val SectionPanel: FC<SectionPanelProps> = FC { props ->
    Panel {
        div {
            css { flexColumn(gap = 16.px) }
            SectionLabel { text = props.title }
            +props.children
        }
    }
}

private external interface SectionLabelProps : Props {
    var text: String
}

private val SectionLabel: FC<SectionLabelProps> = FC { props ->
    span {
        css {
            +SchedulerText.labelSmall
            textTransform = TextTransform.uppercase
            color = SchedulerColors.onSurfaceVariant
        }
        +props.text
    }
}

// ---- overview --------------------------------------------------------------------------------

private external interface OverviewPanelProps : Props {
    var job: JobView
    var timeAbsolute: Boolean
}

private val OverviewPanel: FC<OverviewPanelProps> = FC { props ->
    val job = props.job
    val absolute = props.timeAbsolute

    SectionPanel {
        title = "Overview"

        FactCell {
            label = "Job ID"
            value = job.id
            mono = true
            copyable = true
        }
        FactCell {
            label = "Payload type"
            value = job.payloadType
            mono = true
            copyable = true
        }
        factRow {
            FactCell {
                label = "Queue"
                value = job.queue
            }
            FactCell {
                label = "Priority"
                value = job.priority.value.toString()
            }
        }
        factRow {
            FactCell {
                label = "Attempts"
                value = "${job.attempts} / ${job.maxAttempts}"
            }
            FactCell {
                label = "Duration"
                value = job.durationMs?.let { "$it ms" } ?: "—"
            }
        }
        factRow {
            FactCell {
                label = "Locked by"
                value = job.lockedBy ?: "—"
                mono = job.lockedBy != null
            }
            FactCell {
                label = "Scheduled"
                value = job.scheduledAt?.let { fmtTime(it, absolute) } ?: "—"
            }
        }
        factRow {
            FactCell {
                label = "Created"
                value = fmtTime(job.createdAt, absolute)
            }
            FactCell {
                label = "Updated"
                value = fmtTime(job.updatedAt, absolute)
            }
        }
        job.progress?.let { p ->
            div {
                css {
                    height = 1.px
                    backgroundColor = SchedulerColors.outlineVariant
                }
            }
            ProgressBlock {
                progress = p
                message = job.progressMsg
                succeeded = job.progressSucceeded
                failed = job.progressFailed
                total = job.progressTotal
            }
        }
    }
}

private fun react.ChildrenBuilder.factRow(content: react.ChildrenBuilder.() -> Unit) {
    div {
        css { flexRow(gap = 24.px, align = AlignItems.flexStart) }
        content()
    }
}

private external interface FactCellProps : Props {
    var label: String
    var value: String
    var mono: Boolean?
    var copyable: Boolean?
}

private val FactCell: FC<FactCellProps> = FC { props ->
    div {
        css {
            flexColumn(gap = 3.px)
            flexGrow = number(1.0)
            flexBasis = 0.px
            minWidth = 0.px
        }
        SectionLabel { text = props.label }
        if (props.copyable == true) {
            CopyableText {
                text = props.value
                if (props.mono == true) style = SchedulerText.mono
                color = SchedulerColors.onSurface
            }
        } else {
            span {
                css {
                    if (props.mono == true) +SchedulerText.mono
                    color = SchedulerColors.onSurface
                }
                +props.value
            }
        }
    }
}

private external interface ProgressBlockProps : Props {
    var progress: Float
    var message: String?
    var succeeded: Long?
    var failed: Long?
    var total: Long?
}

private val ProgressBlock: FC<ProgressBlockProps> = FC { props ->
    val fraction = props.progress.coerceIn(0f, 1f)
    val pct = (fraction * 100).toInt()
    val succeeded = props.succeeded
    val failed = props.failed
    val total = props.total
    // A counting bar (JobContext.progressBar) splits into green/red; a plain updateProgress
    // handler only reports a fraction, which renders as one cobalt fill.
    val counting = succeeded != null && failed != null && total != null && total > 0L

    div {
        css { flexColumn(gap = 6.px) }

        div {
            css { flexRow(gap = 8.px) }
            SectionLabel { text = "Progress" }
            span {
                css { +SchedulerText.mono }
                +"$pct%"
            }
        }

        div {
            css {
                flexRow()
                width = 100.pct
                height = 10.px
                borderRadius = SchedulerRadius.extraSmall
                overflow = web.cssom.Overflow.hidden
                backgroundColor = SchedulerColors.surfaceContainerHigh
            }
            if (counting) {
                val succeededFrac = (succeeded!!.toDouble() / total!!).coerceIn(0.0, 1.0)
                val failedFrac = (failed!!.toDouble() / total).coerceIn(0.0, 1.0 - succeededFrac)
                progressSegment(succeededFrac, SchedulerColors.success, key = "succeeded")
                progressSegment(failedFrac, SchedulerColors.error, key = "failed")
            } else {
                progressSegment(fraction.toDouble(), SchedulerColors.primary, key = "progress")
            }
        }

        if (counting) {
            div {
                css { flexRow(gap = 12.px) }
                span {
                    css { color = SchedulerColors.success }
                    +"✓ $succeeded"
                }
                span {
                    css { color = SchedulerColors.error }
                    +"✗ $failed"
                }
                span {
                    css { color = SchedulerColors.onSurfaceVariant }
                    +"/ $total"
                }
            }
        }

        props.message?.takeIf { it.isNotBlank() }?.let { msg ->
            span {
                css {
                    +SchedulerText.bodySmall
                    color = SchedulerColors.onSurfaceVariant
                }
                +msg
            }
        }
    }
}

/**
 * One fill of the progress bar.
 *
 * Rendered even at zero width, and always with a width transition: a running job reports progress
 * every few seconds, and without this the bar teleports to its new length on each update. Keeping
 * the element mounted at zero matters too — a segment that only appears once its count goes above
 * zero (the failed one, typically) would otherwise pop in at full size instead of growing.
 */
private fun react.ChildrenBuilder.progressSegment(fraction: Double, fill: Color, key: String) {
    div {
        this.key = Key(key)
        css {
            width = (fraction.coerceIn(0.0, 1.0) * 100).pct
            height = 100.pct
            backgroundColor = fill
            asDynamic().transition = "width 0.45s cubic-bezier(0.4, 0, 0.2, 1)"
        }
    }
}

// ---- payload ---------------------------------------------------------------------------------

private external interface PayloadPanelProps : Props {
    var job: JobView
    var payloadJson: String
}

private val PayloadPanel: FC<PayloadPanelProps> = FC { props ->
    SectionPanel {
        title = "Payload"
        // Function-ref jobs render as `Mailer.send(123, "welcome")`; everything else as raw JSON.
        val formatted = if (props.job.payloadType == FunctionRefPayload.FUNCTION_REF_PAYLOAD_TYPE) {
            FunctionRefPayloadFormatter.tryFormat(props.payloadJson)
        } else {
            null
        }
        if (formatted != null) {
            FunctionRefPayloadBlock {
                this.formatted = formatted
                rawJson = props.payloadJson
            }
        } else {
            CodeBlock { text = props.payloadJson }
        }
    }
}

private external interface CodeBlockProps : Props {
    var text: String
    var tone: CodeTone?
}

private enum class CodeTone { NEUTRAL, ERROR }

private val CodeBlock: FC<CodeBlockProps> = FC { props ->
    val error = props.tone == CodeTone.ERROR
    pre {
        css {
            margin = 0.px
            padding = 12.px
            borderRadius = SchedulerRadius.small
            backgroundColor = if (error) SchedulerColors.errorContainer else SchedulerColors.surfaceContainerLow
            color = if (error) SchedulerColors.onErrorContainer else SchedulerColors.onSurface
            if (!error) hairline()
            +SchedulerText.mono
            // Long JSON and stack traces wrap instead of forcing the whole panel to scroll.
            whiteSpace = WhiteSpace.preWrap
            asDynamic()["overflow-wrap"] = "anywhere"
        }
        +props.text
    }
}

private external interface FunctionRefPayloadBlockProps : Props {
    var formatted: FunctionRefPayloadFormatter.Formatted
    var rawJson: String
}

private val FunctionRefPayloadBlock: FC<FunctionRefPayloadBlockProps> = FC { props ->
    var showRaw by useState(false)
    val formatted = props.formatted

    div {
        css { flexColumn(gap = 8.px) }

        CodeBlock { text = formatted.oneLine }

        if (formatted.args.isNotEmpty()) {
            div {
                css { flexColumn(gap = 2.px) }
                formatted.args.forEachIndexed { idx, arg ->
                    span {
                        key = Key(idx)
                        css {
                            +SchedulerText.mono
                            color = SchedulerColors.onSurfaceVariant
                        }
                        +"arg[$idx] (${arg.type}) = ${arg.valueRendered}"
                    }
                }
            }
        }

        formatted.targetQualifier?.let {
            span {
                css { color = SchedulerColors.onSurfaceVariant }
                +"Koin qualifier: \"$it\""
            }
        }
        span {
            css { color = SchedulerColors.onSurfaceVariant }
            +"Receiver: ${formatted.receiverFqn}"
        }

        LinkButton {
            label = if (showRaw) "Hide raw JSON" else "Show raw JSON"
            onSelect = { showRaw = !showRaw }
        }
        if (showRaw) {
            CodeBlock { text = props.rawJson }
        }
    }
}

// ---- timeline --------------------------------------------------------------------------------

private external interface TimelinePanelProps : Props {
    var events: List<JobEventDto>
    var timeAbsolute: Boolean
}

private val TimelinePanel: FC<TimelinePanelProps> = FC { props ->
    SectionPanel {
        title = "Timeline · ${props.events.size}"
        if (props.events.isEmpty()) {
            span {
                css { color = SchedulerColors.onSurfaceVariant }
                +"No events recorded yet"
            }
        } else {
            div {
                css { flexColumn() }
                props.events.forEachIndexed { index, event ->
                    TimelineRow {
                        key = Key(event.id.toString())
                        this.event = event
                        isFirst = index == 0
                        isLast = index == props.events.lastIndex
                        timeAbsolute = props.timeAbsolute
                    }
                }
            }
        }
    }
}

private external interface TimelineRowProps : Props {
    var event: JobEventDto
    var isFirst: Boolean
    var isLast: Boolean
    var timeAbsolute: Boolean
}

private val TimelineRow: FC<TimelineRowProps> = FC { props ->
    var stackExpanded by useState(false)
    val event = props.event

    div {
        css { flexRow(gap = 14.px, align = AlignItems.stretch) }

        // Rail gutter: a continuous vertical line with a dot per event, clipped at both ends.
        div {
            css {
                width = 16.px
                flexShrink = number(0.0)
                position = web.cssom.Position.relative
            }
            div {
                css {
                    position = web.cssom.Position.absolute
                    left = 7.px
                    width = 2.px
                    top = if (props.isFirst) 16.px else 0.px
                    bottom = if (props.isLast) 100.pct else 0.px
                    backgroundColor = SchedulerColors.outlineVariant
                }
            }
            div {
                css {
                    position = web.cssom.Position.absolute
                    left = 3.px
                    top = 11.px
                    width = 10.px
                    height = 10.px
                    borderRadius = SchedulerRadius.pill
                    backgroundColor = eventColor(event.eventType)
                }
            }
        }

        div {
            css {
                flexColumn(gap = 4.px)
                flexGrow = number(1.0)
                minWidth = 0.px
                paddingBottom = 16.px
            }

            div {
                css { flexRow(gap = 8.px) }
                span {
                    css {
                        +SchedulerText.labelMedium
                        color = SchedulerColors.onSurface
                    }
                    +event.eventType
                }
                event.prevState?.let { StateChip { state = it } }
                if (event.prevState != null && event.newState != null) {
                    span {
                        css { color = SchedulerColors.onSurfaceVariant }
                        +"→"
                    }
                }
                event.newState?.let { StateChip { state = it } }
                span {
                    css {
                        marginLeft = Auto.auto
                        +SchedulerText.labelSmall
                        color = SchedulerColors.onSurfaceVariant
                        whiteSpace = WhiteSpace.nowrap
                    }
                    +fmtTime(event.occurredAt, props.timeAbsolute)
                }
            }

            event.actor?.let {
                span {
                    css { color = SchedulerColors.onSurfaceVariant }
                    +"by $it"
                }
            }

            event.errorMsg?.let { msg ->
                div {
                    css { flexRow(gap = 8.px, align = AlignItems.flexStart) }
                    span {
                        css {
                            flexGrow = number(1.0)
                            color = SchedulerColors.error
                        }
                        +msg
                    }
                    CopyButton {
                        value = msg
                        label = "Copy"
                    }
                }
            }

            event.errorStack?.let { stack ->
                div {
                    css { flexRow(gap = 12.px) }
                    LinkButton {
                        label = if (stackExpanded) "Hide stack trace" else "Show stack trace"
                        onSelect = { stackExpanded = !stackExpanded }
                    }
                    CopyButton {
                        value = stack
                        label = "Copy trace"
                    }
                }
                if (stackExpanded) {
                    CodeBlock {
                        text = stack
                        tone = CodeTone.ERROR
                    }
                }
            }
        }
    }
}

// One timestamp, not two — honours the header's relative/absolute toggle.
private fun fmtTime(instant: Instant, absolute: Boolean): String =
    if (absolute) formatDateTime(instant) else timeAgo(instant)

private fun eventColor(type: String): Color = type.uppercase().let { t ->
    when {
        "FAIL" in t || "TIMEOUT" in t || "ERROR" in t -> SchedulerColors.error
        "SUCC" in t -> SchedulerColors.success
        "CANCEL" in t -> SchedulerColors.onSurfaceVariant
        "RETRY" in t -> SchedulerColors.warning
        else -> SchedulerColors.primary
    }
}

private external interface LinkButtonProps : Props {
    var label: String
    var onSelect: () -> Unit
}

/** Inline text link — disclosure toggles that shouldn't carry a button's visual weight. */
private val LinkButton: FC<LinkButtonProps> = FC { props ->
    span {
        onClick = { props.onSelect() }
        css {
            +SchedulerText.labelSmall
            color = SchedulerColors.primary
            cursor = Cursor.pointer
            whiteSpace = WhiteSpace.nowrap
            hover { asDynamic()["text-decoration"] = "underline" }
        }
        +props.label
    }
}

private external interface CopyButtonProps : Props {
    var value: String
    var label: String
}

/** Inline link-style copy action that flashes "Copied ✓" briefly. */
private val CopyButton: FC<CopyButtonProps> = FC { props ->
    var copied by useState(false)

    useEffect(copied) {
        if (copied) {
            delay(COPIED_FEEDBACK_MS)
            copied = false
        }
    }

    LinkButton {
        label = if (copied) "Copied ✓" else props.label
        onSelect = {
            navigator.clipboard.writeTextAsync(props.value)
            copied = true
        }
    }
}

private const val COPIED_FEEDBACK_MS = 1400L

// ---- actions sidebar ---------------------------------------------------------------------------

private external interface ActionsPanelProps : Props {
    var component: JobDetailComponent
    var model: JobDetailComponent.Model
    var job: JobView
}

private val ActionsPanel: FC<ActionsPanelProps> = FC { props ->
    val component = props.component
    val m = props.model
    val job = props.job
    val busy = m.cancelling || m.retrying || m.deleting || m.rerouting || m.rerunning

    SectionPanel {
        title = "Actions"

        if (!job.state.isTerminal) {
            fullWidthButton {
                variant = ButtonVariant.DANGER
                disabled = busy
                onClick = component::onCancelClicked
                +if (m.cancelling) "Cancelling…" else "Cancel job"
            }
        }

        // Manual retry is FAILED-only — SUCCEEDED / CANCELLED are deliberate end states.
        if (job.state == JobState.FAILED) {
            fullWidthButton {
                variant = ButtonVariant.FILLED
                disabled = busy
                onClick = component::onRetryClicked
                +if (m.retrying) "Retrying…" else "Retry (fresh budget)"
            }
            fullWidthButton {
                disabled = busy
                onClick = component::onRetryOnceClicked
                +if (m.retrying) "Retrying…" else "Retry +1"
            }
        }

        // Re-route — non-terminal only. The toggle reveals an inline node/tag form.
        if (!job.state.isTerminal) {
            fullWidthButton {
                disabled = m.rerouting
                onClick = component::onRerouteFormToggled
                +if (m.rerouteFormOpen) "Cancel re-route" else "Re-route"
            }
            if (m.rerouteFormOpen) {
                RerouteForm {
                    this.component = component
                    model = m
                }
            }
        }

        // Re-run — clone this job into a fresh ENQUEUED one. Terminal-only in the UI (re-running
        // an in-flight job would just create a duplicate); the backend allows any state. On
        // success the component navigates to the new job, so there's no result banner here.
        if (job.state.isTerminal) {
            fullWidthButton {
                disabled = busy
                onClick = component::onRerunClicked
                +if (m.rerunning) "Re-running…" else "Re-run"
            }
        }

        // Delete — terminal-only, two-step inline confirm.
        if (job.state.isTerminal) {
            fullWidthButton {
                variant = ButtonVariant.DANGER
                disabled = m.deleting
                onClick = component::onDeleteClicked
                +when {
                    m.deleting -> "Deleting…"
                    m.confirmingDelete -> "Click again to confirm"
                    else -> "Delete"
                }
            }
            if (m.confirmingDelete && !m.deleting) {
                fullWidthButton {
                    onClick = component::onDeleteConfirmCancelled
                    +"Cancel delete"
                }
            }
        }

        m.cancelResult?.let { ResultBanner { label = "Cancel"; value = it.name } }
        m.retryResult?.let { ResultBanner { label = "Retry"; value = it.name } }
        m.deleteResult?.let { ResultBanner { label = "Delete"; value = it.name } }
        m.rerouteResult?.let { ResultBanner { label = "Re-route"; value = it.name } }
        m.error?.let {
            ResultBanner {
                label = "Error"
                value = it
                isError = true
            }
        }
    }
}

/** Sidebar buttons all span the panel — one call site keeps that consistent. */
private fun react.ChildrenBuilder.fullWidthButton(block: cs.trade.scheduler.core.frontend.ui.ButtonProps.() -> Unit) {
    div {
        css {
            width = 100.pct
            // The Button itself is inline-flex; this wrapper stretches it.
            asDynamic()["& > button"] = js("({width: '100%', justifyContent: 'center'})")
        }
        Button(block)
    }
}

private external interface RerouteFormProps : Props {
    var component: JobDetailComponent
    var model: JobDetailComponent.Model
}

private val RerouteForm: FC<RerouteFormProps> = FC { props ->
    val component = props.component
    val m = props.model

    div {
        css {
            flexColumn(gap = 8.px)
            paddingTop = 8.px
        }
        span {
            css {
                +SchedulerText.bodySmall
                color = SchedulerColors.onSurfaceVariant
            }
            +"Fill node OR tag (both empty → default queue):"
        }
        TextInput {
            value = m.rerouteNode
            placeholder = "Target node"
            disabled = m.rerouting
            width = 100.pct
            onValueChange = component::onRerouteNodeChanged
            onSubmit = component::onRerouteSubmit
        }
        TextInput {
            value = m.rerouteTag
            placeholder = "Target tag"
            disabled = m.rerouting
            width = 100.pct
            onValueChange = component::onRerouteTagChanged
            onSubmit = component::onRerouteSubmit
        }
        fullWidthButton {
            variant = ButtonVariant.FILLED
            size = ButtonSize.SMALL
            disabled = m.rerouting
            onClick = component::onRerouteSubmit
            +if (m.rerouting) "Applying…" else "Apply re-route"
        }
    }
}

private external interface ResultBannerProps : Props {
    var label: String
    var value: String
    var isError: Boolean?
}

private val ResultBanner: FC<ResultBannerProps> = FC { props ->
    val error = props.isError == true
    div {
        css {
            width = 100.pct
            padding = Padding(8.px, 12.px)
            borderRadius = SchedulerRadius.small
            hairline()
            backgroundColor = if (error) SchedulerColors.errorContainer else SchedulerColors.surfaceContainerLow
            color = if (error) SchedulerColors.onErrorContainer else SchedulerColors.primary
            +SchedulerText.bodySmall
        }
        +"${props.label}: ${props.value}"
    }
}
