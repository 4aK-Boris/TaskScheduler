package cs.trade.scheduler.core.frontend.ui

import cs.trade.scheduler.core.frontend.theme.SchedulerColors
import cs.trade.scheduler.core.frontend.theme.SchedulerRadius
import cs.trade.scheduler.core.frontend.theme.SchedulerText
import emotion.react.css
import react.FC
import react.Props
import react.PropsWithChildren
import react.dom.html.ReactHTML.button
import react.dom.html.ReactHTML.div
import react.dom.html.ReactHTML.span
import react.useState
import web.cssom.BoxShadow
import web.cssom.Cursor
import web.cssom.JustifyContent
import web.cssom.Length
import web.cssom.None
import web.cssom.Padding
import web.cssom.Position
import web.cssom.TextAlign
import web.cssom.TextTransform
import web.cssom.WhiteSpace
import web.cssom.integer
import web.cssom.pct
import web.cssom.px
import web.html.ButtonType
import web.html.button as buttonType

/**
 * Anchored dropdown: the caller renders the trigger, this owns the open state, the panel and the
 * dismissal rules.
 *
 * Dismissal uses a transparent full-viewport backdrop behind the panel rather than a document
 * listener. It needs no subscription to tear down, it cannot leak past unmount, and — unlike a
 * blur handler — it can't fire before the click inside the panel is delivered and swallow the
 * very selection the operator just made.
 */
external interface DropdownProps : PropsWithChildren {
    /** Rendered inside the trigger button. */
    var trigger: react.ReactNode?

    var title: String?

    /** Tint the trigger cobalt — signals "a non-default setting is active" without opening it. */
    var triggerAccented: Boolean?

    /** Fixed panel width. A menu with long labels needs one, or it shrink-wraps and wraps text. */
    var width: Length?

    /** Right-align the panel with the trigger. Default for triggers near the viewport edge. */
    var alignEnd: Boolean?
}

public val Dropdown: FC<DropdownProps> = FC { props ->
    var open by useState(false)

    div {
        css { position = Position.relative }

        button {
            type = ButtonType.buttonType
            props.title?.let { title = it }
            onClick = { open = !open }
            css {
                display = web.cssom.Display.inlineFlex
                alignItems = web.cssom.AlignItems.center
                justifyContent = JustifyContent.center
                width = 36.px
                height = 36.px
                borderRadius = SchedulerRadius.small
                hairline()
                backgroundColor = SchedulerColors.surfaceContainerLowest
                color = if (props.triggerAccented == true) SchedulerColors.primary else SchedulerColors.onSurfaceVariant
                cursor = Cursor.pointer
                hover { borderColor = SchedulerColors.outline }
            }
            +props.trigger
        }

        if (open) {
            div {
                // Catches every click that lands outside the panel, including on other controls.
                onClick = { open = false }
                css {
                    position = Position.fixed
                    inset = 0.px
                    zIndex = integer(40)
                }
            }
            div {
                // Selecting an item closes the menu; items that toggle in place stop the event.
                onClick = { open = false }
                css {
                    position = Position.absolute
                    top = 40.px
                    if (props.alignEnd == true) right = 0.px else left = 0.px
                    zIndex = integer(50)
                    props.width?.let { width = it }
                    padding = Padding(6.px, 0.px)
                    backgroundColor = SchedulerColors.surface
                    borderRadius = SchedulerRadius.medium
                    hairline()
                    boxShadow = BoxShadow(0.px, 6.px, 20.px, SchedulerColors.shadow)
                }
                +props.children
            }
        }
    }
}

/** One selectable row. [selected] draws a leading tick, keeping the label text aligned either way. */
external interface MenuItemProps : PropsWithChildren {
    var onSelect: (() -> Unit)?
    var selected: Boolean?

    /** Keep the menu open after selecting — for toggles the operator may flip several times. */
    var keepOpen: Boolean?
}

public val MenuItem: FC<MenuItemProps> = FC { props ->
    div {
        onClick = { event ->
            if (props.keepOpen == true) event.stopPropagation()
            props.onSelect?.invoke()
        }
        css {
            display = web.cssom.Display.flex
            alignItems = web.cssom.AlignItems.center
            gap = 10.px
            padding = Padding(7.px, 12.px)
            cursor = Cursor.pointer
            whiteSpace = WhiteSpace.nowrap
            +SchedulerText.bodyMedium
            color = SchedulerColors.onSurface
            hover { backgroundColor = SchedulerColors.surfaceContainerLow }
        }
        // The tick's slot is always present so labels line up whether or not they're selected.
        span {
            css {
                display = web.cssom.Display.inlineFlex
                width = 16.px
                flexShrink = web.cssom.number(0.0)
                color = SchedulerColors.primary
            }
            if (props.selected == true) {
                CheckIcon { size = 14.px }
            }
        }
        +props.children
    }
}

/** Uppercase group heading inside a menu ("Auto-refresh", "Age column"). */
external interface MenuSectionLabelProps : Props {
    var label: String
}

public val MenuSectionLabel: FC<MenuSectionLabelProps> = FC { props ->
    div {
        css {
            padding = Padding(6.px, 12.px)
            +SchedulerText.labelSmall
            textTransform = TextTransform.uppercase
            color = SchedulerColors.onSurfaceVariant
            textAlign = TextAlign.left
        }
        +props.label
    }
}

public val MenuDivider: FC<Props> = FC {
    div {
        css {
            height = 1.px
            width = 100.pct
            margin = Padding(6.px, 0.px)
            backgroundColor = SchedulerColors.outlineVariant
            border = None.none
        }
    }
}
