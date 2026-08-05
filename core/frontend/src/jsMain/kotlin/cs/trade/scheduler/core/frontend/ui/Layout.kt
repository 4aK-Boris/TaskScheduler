package cs.trade.scheduler.core.frontend.ui

import csstype.PropertiesBuilder
import cs.trade.scheduler.core.frontend.theme.SchedulerColors
import cs.trade.scheduler.core.frontend.theme.SchedulerRadius
import cs.trade.scheduler.core.frontend.theme.SchedulerText
import emotion.react.css
import react.FC
import react.Props
import react.PropsWithChildren
import react.dom.html.ReactHTML.div
import react.dom.html.ReactHTML.span
import web.cssom.AlignItems
import web.cssom.Auto
import web.cssom.Border
import web.cssom.BoxShadow
import web.cssom.BoxSizing
import web.cssom.Color
import web.cssom.Display
import web.cssom.FlexDirection
import web.cssom.JustifyContent
import web.cssom.Length
import web.cssom.LineStyle
import web.cssom.Overflow
import web.cssom.Padding
import web.cssom.TextAlign
import web.cssom.TextOverflow
import web.cssom.WhiteSpace
import web.cssom.pct
import web.cssom.number
import web.cssom.px

// ---------------------------------------------------------------------------------------------
// Style mixins
//
// Compose expressed layout as nested `Row` / `Column` composables. The DOM equivalent is a flex
// container, and wrapping that in a component per axis would put a real element in the tree for
// no gain. These configure the caller's own element instead.
// ---------------------------------------------------------------------------------------------

/** Horizontal flex line. Defaults to vertically centred — what nearly every row wants. */
public fun PropertiesBuilder.flexRow(
    gap: Length? = null,
    align: AlignItems = AlignItems.center,
    justify: JustifyContent? = null,
) {
    display = Display.flex
    flexDirection = FlexDirection.row
    alignItems = align
    justify?.let { justifyContent = it }
    gap?.let { this.gap = it }
}

/** Vertical flex stack. */
public fun PropertiesBuilder.flexColumn(
    gap: Length? = null,
    align: AlignItems? = null,
    justify: JustifyContent? = null,
) {
    display = Display.flex
    flexDirection = FlexDirection.column
    align?.let { alignItems = it }
    justify?.let { justifyContent = it }
    gap?.let { this.gap = it }
}

/** Hairline border in the theme's outline colour — the dashboard's default panel edge. */
public fun PropertiesBuilder.hairline(color: Color = SchedulerColors.outlineVariant) {
    border = Border(1.px, LineStyle.solid, color)
}

/** Truncate one line with an ellipsis. Used for payload previews and long type names. */
public fun PropertiesBuilder.ellipsis() {
    overflow = Overflow.hidden
    textOverflow = TextOverflow.ellipsis
    whiteSpace = WhiteSpace.nowrap
}

// ---------------------------------------------------------------------------------------------
// Components
// ---------------------------------------------------------------------------------------------

/**
 * An elevated content card: surface fill, hairline edge, soft shadow. The dashboard's unit of
 * grouping — tables, stat blocks and detail sections all sit in one.
 */
external interface PanelProps : PropsWithChildren {
    var padded: Boolean?

    /** Let the panel own its horizontal scrolling instead of widening the page (wide tables). */
    var scrollable: Boolean?
}

public val Panel: FC<PanelProps> = FC { props ->
    div {
        css {
            backgroundColor = SchedulerColors.surface
            borderRadius = SchedulerRadius.large
            hairline()
            boxShadow = BoxShadow(0.px, 1.px, 2.px, SchedulerColors.shadow)
            if (props.padded != false) padding = 20.px
            if (props.scrollable == true) overflowX = Auto.auto
        }
        +props.children
    }
}

/** Section heading above a panel: title (and optional subtitle) left, caller's actions right. */
external interface SectionHeaderProps : PropsWithChildren {
    var title: String
    var subtitle: String?
}

public val SectionHeader: FC<SectionHeaderProps> = FC { props ->
    div {
        css {
            flexRow(gap = 16.px, justify = JustifyContent.spaceBetween)
            marginBottom = 16.px
        }
        div {
            css { flexColumn(gap = 2.px) }
            span {
                css {
                    +SchedulerText.titleLarge
                    color = SchedulerColors.onSurface
                }
                +props.title
            }
            props.subtitle?.let { subtitle ->
                span {
                    css {
                        +SchedulerText.bodySmall
                        color = SchedulerColors.onSurfaceVariant
                    }
                    +subtitle
                }
            }
        }
        div {
            css { flexRow(gap = 8.px) }
            +props.children
        }
    }
}

/** The padded page body every screen renders into — keeps gutters consistent across screens. */
public val ScreenBody: FC<PropsWithChildren> = FC { props ->
    div {
        css {
            flexColumn(gap = 20.px)
            padding = Padding(24.px, 24.px)
            boxSizing = BoxSizing.borderBox
            width = 100.pct
        }
        +props.children
    }
}

public val Divider: FC<Props> = FC {
    div {
        css {
            height = 1.px
            width = 100.pct
            backgroundColor = SchedulerColors.outlineVariant
            flexShrink = number(0.0)
        }
    }
}

/**
 * Indeterminate progress ring. Shown while a screen's FIRST load is in flight; later refreshes
 * keep the stale rows on screen instead, so a live table never flashes empty.
 *
 * The rotation keyframes are declared once in `SchedulerGlobalStyles` — per-instance keyframes
 * would make Emotion inject a duplicate rule for every spinner mounted.
 */
external interface SpinnerProps : Props {
    var size: Length?
    var label: String?
}

public val Spinner: FC<SpinnerProps> = FC { props ->
    val edge = props.size ?: 20.px
    div {
        css {
            flexRow(gap = 10.px)
            color = SchedulerColors.onSurfaceVariant
        }
        div {
            css {
                width = edge
                height = edge
                borderRadius = SchedulerRadius.pill
                border = Border(2.px, LineStyle.solid, SchedulerColors.outlineVariant)
                borderTopColor = SchedulerColors.primary
                asDynamic().animation = "sch-spin 0.7s linear infinite"
            }
        }
        props.label?.let {
            span {
                css { +SchedulerText.bodySmall }
                +it
            }
        }
    }
}

/** "Nothing here" placeholder — an empty table is otherwise indistinguishable from a broken one. */
external interface EmptyStateProps : Props {
    var title: String
    var hint: String?
}

public val EmptyState: FC<EmptyStateProps> = FC { props ->
    div {
        css {
            flexColumn(gap = 6.px, align = AlignItems.center)
            padding = Padding(48.px, 24.px)
            textAlign = TextAlign.center
            color = SchedulerColors.onSurfaceVariant
        }
        span {
            css {
                +SchedulerText.titleSmall
                color = SchedulerColors.onSurface
            }
            +props.title
        }
        props.hint?.let {
            span {
                css { +SchedulerText.bodySmall }
                +it
            }
        }
    }
}

/**
 * Error banner for a failed load or action. Errors are surfaced inline rather than as a toast:
 * an operator needs to see WHICH screen failed while the rest of the dashboard keeps updating.
 */
external interface ErrorBannerProps : Props {
    var message: String
    var onRetry: (() -> Unit)?
}

public val ErrorBanner: FC<ErrorBannerProps> = FC { props ->
    div {
        css {
            flexRow(gap = 12.px, justify = JustifyContent.spaceBetween)
            padding = Padding(10.px, 14.px)
            borderRadius = SchedulerRadius.medium
            backgroundColor = SchedulerColors.errorContainer
            color = SchedulerColors.onErrorContainer
            +SchedulerText.bodySmall
        }
        span { +props.message }
        props.onRetry?.let { retry ->
            Button {
                variant = ButtonVariant.TEXT
                size = ButtonSize.SMALL
                onClick = retry
                +"Retry"
            }
        }
    }
}

/** Muted secondary text — timestamps, counts, "of 1,240" suffixes. */
public val Muted: FC<PropsWithChildren> = FC { props ->
    span {
        css { color = SchedulerColors.onSurfaceVariant }
        +props.children
    }
}

/** Monospace inline text — ids, queue names, class names. */
public val Mono: FC<PropsWithChildren> = FC { props ->
    span {
        css { +SchedulerText.mono }
        +props.children
    }
}
