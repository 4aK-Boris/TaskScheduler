package cs.trade.scheduler.dashboard.web.presentation.components

import cs.trade.scheduler.core.frontend.ui.Dropdown
import cs.trade.scheduler.core.frontend.ui.MenuDivider
import cs.trade.scheduler.core.frontend.ui.MenuItem
import cs.trade.scheduler.core.frontend.ui.MenuSectionLabel
import cs.trade.scheduler.core.frontend.ui.TuneIcon
import react.FC
import react.Key
import react.Props
import react.create
import web.cssom.px

/**
 * Settings menu shared by the list screens: an auto-refresh cadence picker plus a
 * relative-vs-absolute toggle for whichever time column the screen shows. The trigger tints
 * cobalt while auto-refresh is on, so the operator can see the screen is live without opening it.
 *
 * [timeSectionLabel] / [relativeLabel] let each screen name its own time column (Jobs
 * "Age column", Recurring "Next / Last columns").
 */
external interface SettingsMenuProps : Props {
    var autoRefreshSeconds: Int?
    var onAutoRefreshChanged: (Int?) -> Unit
    var timeSectionLabel: String
    var relativeLabel: String
    var timeAbsolute: Boolean
    var onTimeModeChanged: (Boolean) -> Unit
    var absoluteLabel: String?

    /**
     * Optional "stick to top" toggle (Jobs only): when both are non-null a List section renders.
     * Keeps the list pinned to the newest rows so an auto-refresh doesn't push the view down.
     */
    var stickToTop: Boolean?
    var onStickToTopChanged: ((Boolean) -> Unit)?
}

public val SettingsMenu: FC<SettingsMenuProps> = FC { props ->
    Dropdown {
        title = "Display settings"
        triggerAccented = props.autoRefreshSeconds != null
        trigger = TuneIcon.create { size = 16.px }
        alignEnd = true
        // Fixed width so the longest item — "Absolute (DD.MM.YYYY HH:mm:ss)" — sits on one line;
        // the menu otherwise shrink-wraps to a width that wraps that label.
        width = 300.px

        MenuSectionLabel { label = "Auto-refresh" }
        REFRESH_OPTIONS.forEach { (optionLabel, seconds) ->
            MenuItem {
                key = Key(optionLabel)
                selected = props.autoRefreshSeconds == seconds
                onSelect = { props.onAutoRefreshChanged(seconds) }
                +optionLabel
            }
        }

        MenuDivider()

        MenuSectionLabel { label = props.timeSectionLabel }
        MenuItem {
            selected = !props.timeAbsolute
            onSelect = { props.onTimeModeChanged(false) }
            +props.relativeLabel
        }
        MenuItem {
            selected = props.timeAbsolute
            onSelect = { props.onTimeModeChanged(true) }
            +(props.absoluteLabel ?: "Absolute (DD.MM.YYYY HH:mm:ss)")
        }

        val stick = props.stickToTop
        val onStickChanged = props.onStickToTopChanged
        if (stick != null && onStickChanged != null) {
            MenuDivider()
            MenuSectionLabel { label = "List" }
            MenuItem {
                selected = stick
                // Toggle in place — leave the menu open so the operator sees the check flip.
                keepOpen = true
                onSelect = { onStickChanged(!stick) }
                +"Stick to top on refresh"
            }
        }
    }
}

private val REFRESH_OPTIONS: List<Pair<String, Int?>> = listOf(
    "Off" to null,
    "1 second" to 1,
    "3 seconds" to 3,
    "5 seconds" to 5,
    "10 seconds" to 10,
    "30 seconds" to 30,
    "1 minute" to 60,
)
