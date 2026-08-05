package cs.trade.scheduler.dashboard.web.presentation.screens.stats

import cs.trade.scheduler.core.frontend.react.useValue
import cs.trade.scheduler.core.frontend.theme.SchedulerColors
import cs.trade.scheduler.core.frontend.theme.SchedulerRadius
import cs.trade.scheduler.core.frontend.theme.SchedulerText
import cs.trade.scheduler.core.frontend.ui.Button
import cs.trade.scheduler.core.frontend.ui.ErrorBanner
import cs.trade.scheduler.core.frontend.ui.Panel
import cs.trade.scheduler.core.frontend.ui.flexColumn
import cs.trade.scheduler.core.frontend.ui.flexRow
import cs.trade.scheduler.dashboard.web.presentation.components.PageHeader
import cs.trade.scheduler.dashboard.web.presentation.components.RangeSegments
import cs.trade.scheduler.dashboard.web.presentation.screens.typesstats.label
import cs.trade.scheduler.shared.JobState
import cs.trade.scheduler.shared.dto.StatsOverviewResponse
import emotion.react.css
import react.FC
import react.Key
import react.Props
import react.PropsWithChildren
import react.dom.html.ReactHTML.div
import react.dom.html.ReactHTML.span
import react.dom.svg.ReactSVG.circle
import react.dom.svg.ReactSVG.svg
import react.useEffect
import react.useState
import web.cssom.AlignItems
import web.cssom.Auto
import web.cssom.Color
import web.cssom.JustifyContent
import web.cssom.Overflow
import web.cssom.Padding
import web.cssom.Position
import web.cssom.TextAlign
import web.cssom.TextTransform
import web.cssom.number
import web.cssom.pct
import web.cssom.px
import kotlin.math.PI
import kotlin.math.roundToInt

/**
 * Fleet-wide overview: five KPI tiles above an outcome donut and a live-pipeline bar chart.
 *
 * Live states (processing, backlog) are current values; the terminal outcome counts are windowed
 * by the range picker, which is why the copy repeats the window under each of those.
 */
external interface StatsContentProps : Props {
    var component: StatsComponent
}

public val StatsContent: FC<StatsContentProps> = FC { props ->
    val component = props.component
    val state = useValue(component.model)

    div {
        css {
            flexColumn()
            height = 100.pct
            minHeight = 0.px
        }

        // Stats has no single count, so the header carries the title and controls only.
        PageHeader {
            title = "Stats"
            RangeSegments {
                current = state.range
                onSelected = component::onRangeChanged
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

        div {
            css {
                flexGrow = number(1.0)
                minHeight = 0.px
                overflowY = Auto.auto
                padding = Padding(0.px, 16.px, 16.px)
            }
            val overview = state.overview
            when {
                state.loading && overview == null -> StatsSkeleton()

                state.error != null -> ErrorBanner {
                    message = "Error: ${state.error}"
                    onRetry = component::onRefreshClicked
                }

                overview != null -> Dashboard {
                    this.overview = overview
                    window = state.range.label().lowercase()
                }
            }
        }
    }
}

private external interface DashboardProps : Props {
    var overview: StatsOverviewResponse
    var window: String
}

private val Dashboard: FC<DashboardProps> = FC { props ->
    val o = props.overview
    val window = props.window
    val backlog = o.enqueued + o.scheduled + o.awaitingDeps
    val rate = (o.succeeded + o.failed).takeIf { it > 0 }?.let { o.succeeded.toDouble() / it }

    // Grow the charts in once when the screen first appears. Distracting on every silent refresh,
    // so it is keyed to mount only — after the first frame `grown` stays true.
    var grown by useState(false)
    useEffect(Unit) { grown = true }

    div {
        css { flexColumn(gap = 16.px) }

        div {
            css { flexRow(gap = 16.px, align = AlignItems.stretch) }
            KpiTile {
                label = "In flight"
                value = o.processing.grouped()
                valueColor = SchedulerColors.secondary
                sub = "running now"
            }
            KpiTile {
                label = "Backlog"
                value = backlog.grouped()
                valueColor = SchedulerColors.primary
                sub = "enqueued · scheduled · deps"
            }
            KpiTile {
                label = "Succeeded"
                value = o.succeeded.grouped()
                valueColor = SchedulerColors.success
                sub = window
            }
            KpiTile {
                label = "Failed"
                value = o.failed.grouped()
                valueColor = if (o.failed > 0) SchedulerColors.error else SchedulerColors.onSurface
                sub = window
            }
            KpiTile {
                label = "Success rate"
                value = rate.asPercent()
                valueColor = rateColor(rate)
                sub = "completed · $window"
            }
        }

        div {
            css { flexRow(gap = 16.px, align = AlignItems.stretch) }
            ChartPanel {
                title = "Outcomes · $window"
                grow = 1.0
                OutcomeDonut {
                    succeeded = o.succeeded
                    failed = o.failed
                    cancelled = o.cancelled
                    successRate = rate
                    this.grown = grown
                }
            }
            ChartPanel {
                title = "Active pipeline"
                grow = 1.5
                PipelineBars {
                    counts = listOf(
                        JobState.SCHEDULED to o.scheduled,
                        JobState.AWAITING_DEPS to o.awaitingDeps,
                        JobState.ENQUEUED to o.enqueued,
                        JobState.PROCESSING to o.processing,
                        JobState.AWAITING_RETRY to o.awaitingRetry,
                    )
                    this.grown = grown
                }
            }
        }

        span {
            css {
                +SchedulerText.bodySmall
                color = SchedulerColors.onSurfaceVariant
                paddingLeft = 4.px
            }
            +"Outcomes counted over $window · live states are current · updates over WebSocket"
        }
    }
}

private external interface KpiTileProps : Props {
    var label: String
    var value: String
    var valueColor: Color
    var sub: String
}

private val KpiTile: FC<KpiTileProps> = FC { props ->
    div {
        css {
            flexGrow = number(1.0)
            flexBasis = 0.px
        }
        Panel {
            div {
                css {
                    flexColumn(gap = 10.px, justify = JustifyContent.spaceBetween)
                    height = 116.px
                    boxSizing = web.cssom.BoxSizing.borderBox
                }
                span {
                    css {
                        +SchedulerText.labelSmall
                        textTransform = TextTransform.uppercase
                        color = SchedulerColors.onSurfaceVariant
                    }
                    +props.label
                }
                span {
                    css {
                        +SchedulerText.headlineMedium
                        color = props.valueColor
                    }
                    +props.value
                }
                span {
                    css {
                        +SchedulerText.bodySmall
                        color = SchedulerColors.onSurfaceVariant
                    }
                    +props.sub
                }
            }
        }
    }
}

private external interface ChartPanelProps : PropsWithChildren {
    var title: String

    /** Flex weight — the pipeline chart gets 1.5× the donut's width. */
    var grow: Double
}

private val ChartPanel: FC<ChartPanelProps> = FC { props ->
    div {
        css {
            flexGrow = number(props.grow)
            flexBasis = 0.px
            minWidth = 0.px
        }
        Panel {
            div {
                css { flexColumn(gap = 20.px) }
                span {
                    css {
                        +SchedulerText.labelSmall
                        textTransform = TextTransform.uppercase
                        color = SchedulerColors.onSurfaceVariant
                    }
                    +props.title
                }
                +props.children
            }
        }
    }
}

private external interface OutcomeDonutProps : Props {
    var succeeded: Long
    var failed: Long
    var cancelled: Long
    var successRate: Double?
    var grown: Boolean
}

/**
 * Ring chart of terminal outcomes with the success rate in the hub; the legend lists the counts.
 *
 * Drawn with one SVG circle per segment using `stroke-dasharray` — the arc length is a plain CSS
 * property, so growing the ring in is a transition rather than a per-frame redraw.
 */
private val OutcomeDonut: FC<OutcomeDonutProps> = FC { props ->
    val total = props.succeeded + props.failed + props.cancelled
    // SVG `stroke` takes a plain string, so segments carry the raw custom-property reference
    // rather than a typed cssom colour.
    val segments = listOf(
        DonutSegment("Succeeded", props.succeeded, SchedulerColors.success, "var(--sch-success)"),
        DonutSegment("Failed", props.failed, SchedulerColors.error, "var(--sch-error)"),
        DonutSegment("Cancelled", props.cancelled, SchedulerColors.onSurfaceVariant, "var(--sch-on-surface-variant)"),
    ).filter { it.value > 0 }

    div {
        css { flexRow(gap = 24.px) }

        div {
            css {
                position = Position.relative
                width = DONUT_BOX.px
                height = DONUT_BOX.px
                flexShrink = number(0.0)
            }
            svg {
                css {
                    width = 100.pct
                    height = 100.pct
                    // Start the first segment at twelve o'clock instead of three.
                    asDynamic().transform = "rotate(-90deg)"
                }
                // Track ring behind the segments — keeps the chart legible at zero.
                circle {
                    cx = DONUT_BOX / 2
                    cy = DONUT_BOX / 2
                    r = DONUT_RADIUS
                    fill = "none"
                    stroke = "var(--sch-surface-container-high)"
                    strokeWidth = DONUT_STROKE
                }
                var offset = 0.0
                segments.forEach { segment ->
                    val fraction = if (total > 0) segment.value.toDouble() / total else 0.0
                    val length = if (props.grown) fraction * DONUT_CIRCUMFERENCE else 0.0
                    circle {
                        key = Key(segment.label)
                        cx = DONUT_BOX / 2
                        cy = DONUT_BOX / 2
                        r = DONUT_RADIUS
                        fill = "none"
                        stroke = segment.strokeValue
                        strokeWidth = DONUT_STROKE
                        // The arc is expressed as a dash length, so growing the ring in is a
                        // plain CSS transition rather than a per-frame redraw.
                        asDynamic().strokeDasharray = "$length $DONUT_CIRCUMFERENCE"
                        asDynamic().strokeDashoffset = -offset
                        css {
                            asDynamic().transition =
                                "stroke-dasharray ${GROW_MS}ms cubic-bezier(0.4,0,0.2,1), " +
                                "stroke-dashoffset ${GROW_MS}ms cubic-bezier(0.4,0,0.2,1)"
                        }
                    }
                    offset += length
                }
            }
            div {
                css {
                    position = Position.absolute
                    inset = 0.px
                    flexColumn(gap = 2.px, align = AlignItems.center, justify = JustifyContent.center)
                }
                span {
                    css {
                        +SchedulerText.headlineMedium
                        color = SchedulerColors.onSurface
                    }
                    +props.successRate.asPercent()
                }
                span {
                    css {
                        +SchedulerText.labelSmall
                        color = SchedulerColors.onSurfaceVariant
                    }
                    +"success"
                }
            }
        }

        div {
            css { flexColumn(gap = 10.px) }
            if (segments.isEmpty()) {
                span {
                    css {
                        +SchedulerText.bodySmall
                        color = SchedulerColors.onSurfaceVariant
                    }
                    +"No completed jobs"
                }
            } else {
                segments.forEach { segment ->
                    div {
                        key = Key(segment.label)
                        css { flexRow(gap = 8.px) }
                        span {
                            css {
                                width = 10.px
                                height = 10.px
                                borderRadius = SchedulerRadius.pill
                                backgroundColor = segment.swatch
                                flexShrink = number(0.0)
                            }
                        }
                        span {
                            css { width = 86.px }
                            +segment.label
                        }
                        span {
                            css { +SchedulerText.mono }
                            +segment.value.grouped()
                        }
                    }
                }
            }
        }
    }
}

private external interface PipelineBarsProps : Props {
    var counts: List<Pair<JobState, Long>>
    var grown: Boolean
}

/** Horizontal bar chart of the live (non-terminal) states, each bar proportional to the busiest. */
private val PipelineBars: FC<PipelineBarsProps> = FC { props ->
    val max = props.counts.maxOf { it.second }.coerceAtLeast(1)
    div {
        css { flexColumn(gap = 14.px) }
        props.counts.forEach { (state, count) ->
            div {
                key = Key(state.name)
                css { flexRow(gap = 12.px) }
                span {
                    css {
                        width = 128.px
                        flexShrink = number(0.0)
                        color = SchedulerColors.onSurfaceVariant
                    }
                    +state.pretty()
                }
                div {
                    css {
                        flexGrow = number(1.0)
                        height = 22.px
                        borderRadius = SchedulerRadius.medium
                        overflow = Overflow.hidden
                        backgroundColor = SchedulerColors.surfaceContainerHigh
                    }
                    div {
                        css {
                            height = 100.pct
                            width = if (props.grown) (count.toDouble() / max * 100).pct else 0.pct
                            borderRadius = SchedulerRadius.medium
                            backgroundColor = chartColor(state)
                            asDynamic().transition = "width ${GROW_MS}ms cubic-bezier(0.4, 0, 0.2, 1)"
                        }
                    }
                }
                span {
                    css {
                        width = 72.px
                        flexShrink = number(0.0)
                        textAlign = TextAlign.right
                        +SchedulerText.mono
                    }
                    +count.grouped()
                }
            }
        }
    }
}

private val StatsSkeleton: FC<Props> = FC {
    div {
        css { flexColumn(gap = 16.px) }
        div {
            css { flexRow(gap = 16.px) }
            repeat(5) { index ->
                skeletonBlock(key = "kpi-$index", grow = 1.0, blockHeight = 116)
            }
        }
        div {
            css { flexRow(gap = 16.px) }
            skeletonBlock(key = "chart-0", grow = 1.0, blockHeight = 260)
            skeletonBlock(key = "chart-1", grow = 1.5, blockHeight = 260)
        }
    }
}

private fun react.ChildrenBuilder.skeletonBlock(key: String, grow: Double, blockHeight: Int) {
    div {
        this.key = Key(key)
        css {
            flexGrow = number(grow)
            flexBasis = 0.px
            height = blockHeight.px
            borderRadius = SchedulerRadius.medium
            backgroundColor = SchedulerColors.surfaceContainerHigh
        }
    }
}

/**
 * One outcome slice. Carries the colour twice because the legend swatch is styled through Emotion
 * (typed cssom [Color]) while the ring is an SVG `stroke` attribute (plain string).
 */
private class DonutSegment(
    val label: String,
    val value: Long,
    val swatch: Color,
    val strokeValue: String,
)

private const val GROW_MS = 700
private const val DONUT_BOX = 172.0
private const val DONUT_STROKE = 24.0
private const val DONUT_RADIUS = (DONUT_BOX - DONUT_STROKE) / 2
private val DONUT_CIRCUMFERENCE = 2 * PI * DONUT_RADIUS

// Saturated, theme-aware fill per state — the pale chip containers in JobStateColors wash out as
// chart fills, so charts use the strong colour roles instead.
private fun chartColor(state: JobState): Color = when (state) {
    JobState.SCHEDULED -> SchedulerColors.tertiary
    JobState.AWAITING_DEPS -> SchedulerColors.outline
    JobState.ENQUEUED -> SchedulerColors.primary
    JobState.PROCESSING -> SchedulerColors.secondary
    JobState.AWAITING_RETRY -> SchedulerColors.warning
    JobState.SUCCEEDED -> SchedulerColors.success
    JobState.FAILED -> SchedulerColors.error
    JobState.CANCELLED -> SchedulerColors.onSurfaceVariant
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

private fun rateColor(rate: Double?): Color = when {
    rate == null -> SchedulerColors.onSurface
    rate >= 0.99 -> SchedulerColors.success
    rate >= 0.90 -> SchedulerColors.warning
    else -> SchedulerColors.error
}

/** `0.99198` → `"99.2%"`, null → `"—"`. One decimal, rounded. */
private fun Double?.asPercent(): String {
    if (this == null) return "—"
    val tenths = (this * 1000).roundToInt()
    return "${tenths / 10}.${tenths % 10}%"
}

/** `184500` → `"184,500"`. No Locale in Kotlin common, so group by hand. */
private fun Long.grouped(): String {
    val digits = toString()
    val sb = StringBuilder()
    for (i in digits.indices) {
        if (i > 0 && (digits.length - i) % 3 == 0) sb.append(',')
        sb.append(digits[i])
    }
    return sb.toString()
}
