package cs.trade.scheduler.dashboard.web.presentation.components

import cs.trade.scheduler.core.frontend.theme.SchedulerColors
import cs.trade.scheduler.core.frontend.theme.SchedulerRadius
import cs.trade.scheduler.core.frontend.theme.SchedulerText
import cs.trade.scheduler.core.frontend.ui.flexRow
import cs.trade.scheduler.core.frontend.ui.hairline
import emotion.react.css
import react.FC
import react.Key
import react.Props
import react.PropsWithChildren
import react.dom.html.ReactHTML.div
import react.dom.html.ReactHTML.span
import web.cssom.BoxSizing
import web.cssom.JustifyContent
import web.cssom.Length
import web.cssom.Auto
import web.cssom.Padding
import web.cssom.number
import web.cssom.pct
import web.cssom.px

// Shared "Graphite" chrome reused across the list screens (Jobs, Recurring, Workers, …) so the
// header / panel / count chip / skeleton stay identical instead of being copy-pasted per screen.

/** Monospace count chip shown next to a screen title. */
external interface CountPillProps : Props {
    var count: Long
}

public val CountPill: FC<CountPillProps> = FC { props ->
    span {
        css {
            +SchedulerText.mono
            backgroundColor = SchedulerColors.surfaceContainerHigh
            color = SchedulerColors.onSurfaceVariant
            borderRadius = SchedulerRadius.small
            padding = Padding(3.px, 8.px)
        }
        +props.count.toString()
    }
}

/** A single grey placeholder bar for loading skeletons. */
external interface SkeletonBarProps : Props {
    var width: Length
}

public val SkeletonBar: FC<SkeletonBarProps> = FC { props ->
    div {
        css {
            width = props.width
            height = 12.px
            backgroundColor = SchedulerColors.surfaceContainerHigh
            borderRadius = SchedulerRadius.extraSmall
        }
    }
}

/**
 * Placeholder rows shown during a screen's first load. A skeleton rather than a spinner because
 * the table's shape is known up front — the layout doesn't jump when the data lands.
 */
external interface SkeletonRowsProps : Props {
    var rows: Int
    var widths: List<Length>
}

public val SkeletonRows: FC<SkeletonRowsProps> = FC { props ->
    div {
        css {
            display = web.cssom.Display.flex
            flexDirection = web.cssom.FlexDirection.column
            gap = 14.px
            padding = Padding(16.px, 12.px)
        }
        repeat(props.rows) { row ->
            div {
                key = Key(row)
                css { flexRow(gap = 24.px) }
                props.widths.forEachIndexed { index, barWidth ->
                    SkeletonBar {
                        key = Key("$row-$index")
                        width = barWidth
                    }
                }
            }
        }
    }
}

/** Screen header on the grey canvas: title + count chip on the left, caller's actions right. */
external interface PageHeaderProps : PropsWithChildren {
    var title: String
    var count: Long?
}

public val PageHeader: FC<PageHeaderProps> = FC { props ->
    div {
        css {
            flexRow(justify = JustifyContent.spaceBetween)
            width = 100.pct
            boxSizing = BoxSizing.borderBox
            padding = Padding(18.px, 24.px, 14.px)
        }
        div {
            css { flexRow(gap = 12.px) }
            span {
                css {
                    +SchedulerText.headlineSmall
                    color = SchedulerColors.onBackground
                }
                +props.title
            }
            props.count?.let { value ->
                CountPill { count = value }
            }
        }
        div {
            css { flexRow(gap = 8.px) }
            +props.children
        }
    }
}

/**
 * White content card floating on the grey canvas — hairline border, crisp radius, fills the rest
 * of the viewport and owns its own scrolling so the page header stays put.
 */
public val DashboardPanel: FC<PropsWithChildren> = FC { props ->
    div {
        css {
            flexGrow = number(1.0)
            minHeight = 0.px
            overflow = Auto.auto
            margin = Padding(0.px, 16.px, 16.px)
            backgroundColor = SchedulerColors.surface
            borderRadius = SchedulerRadius.medium
            hairline()
        }
        +props.children
    }
}

/**
 * The frame every list screen renders into: a fixed [PageHeader] above a scrolling
 * [DashboardPanel]. Screens supply the header actions and the panel body.
 */
external interface ListScreenProps : PropsWithChildren {
    var title: String
    var count: Long?

    /** Header controls, rendered to the right of the title. A slot, not children. */
    var actions: react.ReactNode?
}

public val ListScreen: FC<ListScreenProps> = FC { props ->
    div {
        css {
            display = web.cssom.Display.flex
            flexDirection = web.cssom.FlexDirection.column
            height = 100.pct
            minHeight = 0.px
        }
        PageHeader {
            title = props.title
            count = props.count
            +props.actions
        }
        DashboardPanel {
            +props.children
        }
    }
}
