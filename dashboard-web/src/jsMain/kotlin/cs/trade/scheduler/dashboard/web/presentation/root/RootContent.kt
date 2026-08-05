package cs.trade.scheduler.dashboard.web.presentation.root

import cs.trade.scheduler.core.frontend.react.useValue
import cs.trade.scheduler.core.frontend.theme.SchedulerColors
import cs.trade.scheduler.core.frontend.theme.SchedulerGlobalStyles
import cs.trade.scheduler.core.frontend.theme.SchedulerRadius
import cs.trade.scheduler.core.frontend.theme.SchedulerText
import cs.trade.scheduler.core.frontend.theme.applyThemeMode
import cs.trade.scheduler.core.frontend.ui.BrandMark
import cs.trade.scheduler.core.frontend.ui.Chip
import cs.trade.scheduler.core.frontend.ui.Dot
import cs.trade.scheduler.core.frontend.ui.IconButton
import cs.trade.scheduler.core.frontend.ui.MoonIcon
import cs.trade.scheduler.core.frontend.ui.SearchIcon
import cs.trade.scheduler.core.frontend.ui.SunIcon
import cs.trade.scheduler.core.frontend.ui.flexColumn
import cs.trade.scheduler.core.frontend.ui.flexRow
import cs.trade.scheduler.core.frontend.ui.hairline
import cs.trade.scheduler.dashboard.web.data.connection.ConnectionStatus
import cs.trade.scheduler.dashboard.web.presentation.screens.jobdetail.JobDetailContent
import cs.trade.scheduler.dashboard.web.presentation.screens.joblist.JobListContent
import cs.trade.scheduler.dashboard.web.presentation.screens.recurring.RecurringListContent
import cs.trade.scheduler.dashboard.web.presentation.screens.stats.StatsContent
import cs.trade.scheduler.dashboard.web.presentation.screens.types.TypesContent
import cs.trade.scheduler.dashboard.web.presentation.screens.typesstats.TypeStatsContent
import cs.trade.scheduler.dashboard.web.presentation.screens.upcoming.UpcomingContent
import cs.trade.scheduler.dashboard.web.presentation.screens.workers.WorkersContent
import emotion.react.css
import react.FC
import react.Key
import react.Props
import react.dom.html.ReactHTML.button
import react.dom.html.ReactHTML.div
import react.dom.html.ReactHTML.input
import react.dom.html.ReactHTML.span
import react.useEffect
import react.useState
import web.cssom.AlignItems
import web.cssom.Color
import web.cssom.Cursor
import web.cssom.JustifyContent
import web.cssom.None
import web.cssom.Padding
import web.cssom.Position
import web.cssom.TextTransform
import web.cssom.integer
import web.cssom.number
import web.cssom.pct
import web.cssom.px
import web.cssom.vh
import web.html.ButtonType
import web.html.InputType
import web.html.button as buttonType
import web.html.text as textInput

/**
 * The application shell: a fixed section nav above the active screen.
 *
 * Everything below reads its state from the Decompose component tree via [useValue] — the same
 * components the wasm build used, unchanged. React only renders them.
 */
external interface RootContentProps : Props {
    var component: RootComponent
}

public val RootContent: FC<RootContentProps> = FC { props ->
    val component = props.component
    val isDark = useValue(component.isDarkTheme)
    val stack = useValue(component.stack)
    val connection = useValue(component.connection)
    val active = stack.active.configuration

    // The palette lives in CSS variables keyed off <html data-theme>, so switching themes is an
    // attribute write — no restyling pass through the React tree.
    useEffect(isDark) { applyThemeMode(isDark) }

    SchedulerGlobalStyles()

    div {
        css {
            flexColumn()
            height = 100.vh
            backgroundColor = SchedulerColors.background
        }

        SectionNav {
            this.active = active
            this.connection = connection
            this.isDark = isDark
            onNavigate = { config -> component.navigateTo(config) }
            onToggleTheme = component::onToggleTheme
            onJumpToJob = component::onJumpToJob
        }

        div {
            // Keyed on the active screen so React remounts on navigation — that restarts the
            // fade-in and guarantees no stale scroll position bleeds between screens.
            key = Key(active.toString())
            css {
                flexGrow = number(1.0)
                minHeight = 0.px
                asDynamic().animation = "sch-fade-in 0.28s ease-out"
            }
            when (val child = stack.active.instance) {
                is RootComponent.Child.JobList -> JobListContent { this.component = child.component }
                is RootComponent.Child.JobDetail -> JobDetailContent { this.component = child.component }
                is RootComponent.Child.RecurringList -> RecurringListContent { this.component = child.component }
                is RootComponent.Child.Upcoming -> UpcomingContent { this.component = child.component }
                is RootComponent.Child.Stats -> StatsContent { this.component = child.component }
                is RootComponent.Child.Workers -> WorkersContent { this.component = child.component }
                is RootComponent.Child.Types -> TypesContent { this.component = child.component }
                is RootComponent.Child.TypeStats -> TypeStatsContent { this.component = child.component }
            }
        }
    }
}

/** Dispatch one nav click to the matching component method. */
private fun RootComponent.navigateTo(config: RootComponent.Config) {
    when (config) {
        is RootComponent.Config.JobList -> onNavigateToJobs()
        is RootComponent.Config.Upcoming -> onNavigateToUpcoming()
        is RootComponent.Config.RecurringList -> onNavigateToRecurring()
        is RootComponent.Config.Workers -> onNavigateToWorkers()
        is RootComponent.Config.Types -> onNavigateToTypes()
        is RootComponent.Config.TypeStats -> onNavigateToTypeStats()
        is RootComponent.Config.Stats -> onNavigateToStats()
        is RootComponent.Config.JobDetail -> onJumpToJob(config.jobId)
    }
}

private external interface SectionNavProps : Props {
    var active: RootComponent.Config
    var connection: ConnectionStatus
    var isDark: Boolean
    var onNavigate: (RootComponent.Config) -> Unit
    var onToggleTheme: () -> Unit
    var onJumpToJob: (String) -> Unit
}

private val SectionNav: FC<SectionNavProps> = FC { props ->
    div {
        css {
            flexRow(justify = JustifyContent.spaceBetween)
            height = 64.px
            flexShrink = number(0.0)
            padding = Padding(0.px, 24.px)
            backgroundColor = SchedulerColors.surface
            borderBottom = web.cssom.Border(1.px, web.cssom.LineStyle.solid, SchedulerColors.outlineVariant)
        }

        div {
            css { flexRow(gap = 20.px) }
            div {
                css { flexRow(gap = 10.px) }
                BrandMark { size = 26.px }
                span {
                    css {
                        +SchedulerText.titleMedium
                        fontWeight = integer(700)
                        letterSpacing = 1.5.px
                        color = SchedulerColors.onSurface
                    }
                    +"TASKSCHEDULER"
                }
            }
            div {
                css {
                    height = 26.px
                    width = 1.px
                    backgroundColor = SchedulerColors.outlineVariant
                }
            }
            div {
                css { flexRow(gap = 2.px) }
                NAV_SECTIONS.forEach { section ->
                    NavLink {
                        key = Key(section.label)
                        label = section.label
                        // JobDetail is reached from the Jobs table, so it keeps Jobs lit.
                        isActive = section.matches(props.active)
                        onClick = { props.onNavigate(section.config) }
                    }
                }
            }
        }

        div {
            css { flexRow(gap = 8.px) }
            JobIdSearchBox { onSubmit = props.onJumpToJob }
            ConnectionBadge { status = props.connection }
            IconButton {
                title = if (props.isDark) "Switch to light theme" else "Switch to dark theme"
                onClick = props.onToggleTheme
                size = 36.px
                if (props.isDark) {
                    SunIcon { size = 18.px }
                } else {
                    MoonIcon { size = 18.px }
                }
            }
        }
    }
}

/** One nav section: its label, the config a click pushes, and how it decides it is lit. */
private class NavSection(
    val label: String,
    val config: RootComponent.Config,
    val matches: (RootComponent.Config) -> Boolean,
)

private val NAV_SECTIONS: List<NavSection> = listOf(
    NavSection("Jobs", RootComponent.Config.JobList) {
        it is RootComponent.Config.JobList || it is RootComponent.Config.JobDetail
    },
    NavSection("Upcoming", RootComponent.Config.Upcoming) { it is RootComponent.Config.Upcoming },
    NavSection("Recurring", RootComponent.Config.RecurringList) { it is RootComponent.Config.RecurringList },
    NavSection("Workers", RootComponent.Config.Workers) { it is RootComponent.Config.Workers },
    NavSection("Types", RootComponent.Config.Types) { it is RootComponent.Config.Types },
    NavSection("Type Stats", RootComponent.Config.TypeStats) { it is RootComponent.Config.TypeStats },
    NavSection("Stats", RootComponent.Config.Stats) { it is RootComponent.Config.Stats },
)

private external interface NavLinkProps : Props {
    var label: String
    var isActive: Boolean
    var onClick: () -> Unit
}

/**
 * Uppercase, tracked label with a short cobalt tick under the active section — reads as a precise
 * tab rather than a plain text link. The tick animates its width, so switching sections slides
 * rather than jumps.
 */
private val NavLink: FC<NavLinkProps> = FC { props ->
    button {
        type = ButtonType.buttonType
        onClick = { props.onClick() }
        css {
            flexColumn(align = AlignItems.center)
            gap = 5.px
            padding = Padding(6.px, 10.px)
            border = None.none
            backgroundColor = SchedulerColors.transparent
            cursor = Cursor.pointer
            +SchedulerText.labelLarge
            textTransform = TextTransform.uppercase
            letterSpacing = 0.8.px
            fontWeight = if (props.isActive) integer(600) else integer(500)
            color = if (props.isActive) SchedulerColors.primary else SchedulerColors.onSurfaceVariant
            asDynamic().transition = "color 0.32s ease-out"
            hover { color = SchedulerColors.onSurface }
        }
        +props.label
        span {
            css {
                height = 2.px
                width = if (props.isActive) 18.px else 0.px
                backgroundColor = SchedulerColors.primary
                asDynamic().transition = "width 0.32s ease-out"
            }
        }
    }
}

private external interface JobIdSearchBoxProps : Props {
    var onSubmit: (String) -> Unit
}

/**
 * Compact "command bar": magnifier + monospace UUID input in a crisp hairline field. Enter
 * navigates to JobDetail, which surfaces "not found" for a bad id.
 */
private val JobIdSearchBox: FC<JobIdSearchBoxProps> = FC { props ->
    var value by useState("")

    div {
        css {
            flexRow(gap = 8.px)
            width = 260.px
            height = 34.px
            padding = Padding(0.px, 10.px)
            borderRadius = SchedulerRadius.small
            backgroundColor = SchedulerColors.surfaceContainerHighest
            hairline()
            color = SchedulerColors.onSurfaceVariant
            focusWithin { borderColor = SchedulerColors.primary }
        }
        SearchIcon { size = 14.px }
        input {
            type = InputType.textInput
            this.value = value
            placeholder = "Find job by ID"
            onChange = { event -> value = event.target.value }
            onKeyDown = { event ->
                if (event.key == "Enter") {
                    val trimmed = value.trim()
                    if (trimmed.isNotEmpty()) {
                        props.onSubmit(trimmed)
                        value = ""
                    }
                }
            }
            css {
                flexGrow = number(1.0)
                minWidth = 0.px
                border = None.none
                outline = None.none
                backgroundColor = SchedulerColors.transparent
                color = SchedulerColors.onSurface
                +SchedulerText.mono
                placeholder { color = SchedulerColors.onSurfaceVariant }
            }
        }
    }
}

private external interface ConnectionBadgeProps : Props {
    var status: ConnectionStatus
}

/**
 * Three-state pill: a coloured dot + label. Semantics matter (green = ok, amber = transient,
 * red = no signal); the colours come from the theme's semantic palette so the pill adapts to
 * dark mode instead of staying a light pastel chip.
 */
private val ConnectionBadge: FC<ConnectionBadgeProps> = FC { props ->
    val style = when (props.status) {
        ConnectionStatus.CONNECTED -> BadgeStyle(
            label = "Live",
            dot = SchedulerColors.success,
            container = SchedulerColors.successContainer,
            onContainer = SchedulerColors.onSuccessContainer,
        )

        ConnectionStatus.RECONNECTING -> BadgeStyle(
            label = "Reconnecting…",
            dot = SchedulerColors.warning,
            container = SchedulerColors.warningContainer,
            onContainer = SchedulerColors.onWarningContainer,
        )

        ConnectionStatus.DISCONNECTED -> BadgeStyle(
            label = "Disconnected",
            dot = SchedulerColors.error,
            container = SchedulerColors.errorContainer,
            onContainer = SchedulerColors.onErrorContainer,
        )
    }

    Chip {
        container = style.container
        content = style.onContainer
        Dot {
            color = style.dot
            // Breathing dot while reconnecting — signals "working on it", not a frozen pill.
            pulsing = props.status == ConnectionStatus.RECONNECTING
        }
        +style.label
    }
}

private class BadgeStyle(
    val label: String,
    val dot: Color,
    val container: Color,
    val onContainer: Color,
)
