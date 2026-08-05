package cs.trade.scheduler.dashboard.web.presentation.components

import csstype.Properties
import cs.trade.scheduler.core.frontend.theme.SchedulerColors
import cs.trade.scheduler.core.frontend.ui.CheckIcon
import cs.trade.scheduler.core.frontend.ui.CopyIcon
import cs.trade.scheduler.core.frontend.ui.ellipsis
import cs.trade.scheduler.core.frontend.ui.flexRow
import emotion.react.css
import kotlinx.coroutines.delay
import react.FC
import react.Props
import react.dom.html.ReactHTML.span
import react.useEffect
import react.useState
import web.cssom.Color
import web.cssom.Cursor
import web.cssom.None
import web.cssom.number
import web.cssom.px
import web.navigator.navigator

/**
 * Single-line text that ellipsises when it doesn't fit, reveals the full value in a tooltip on
 * hover, and shows a copy glyph — also on hover — that copies [copyValue] to the clipboard,
 * flashing a checkmark.
 *
 * The glyph is its own click target with `stopPropagation`, so this works inside a row that is
 * itself clickable (the Jobs table navigates on row-click): clicking the glyph copies without
 * also opening the job.
 *
 * The hover reveal is pure CSS — the icon's slot is always laid out and only its opacity changes,
 * so revealing it never shifts the text. On the wasm build this needed an interaction source, a
 * hover state and an animated alpha; here it is three lines of stylesheet.
 */
external interface CopyableTextProps : Props {
    var text: String

    /** Defaults to [text]; carries more when the label is abbreviated (simple name vs FQN). */
    var copyValue: String?
    var tooltip: String?
    var style: Properties?
    var color: Color?
}

public val CopyableText: FC<CopyableTextProps> = FC { props ->
    val value = props.copyValue ?: props.text
    var copied by useState(false)

    // Revert the checkmark after a beat. Keyed on `copied` so a second copy restarts the timer.
    useEffect(copied) {
        if (copied) {
            delay(COPIED_FEEDBACK_MS)
            copied = false
        }
    }

    span {
        css {
            flexRow(gap = 8.px)
            minWidth = 0.px
            // Reveal the glyph when the pointer is anywhere over the text+glyph pair.
            hover {
                descendants(COPY_ICON_CLASS) { opacity = number(1.0) }
            }
        }

        span {
            title = props.tooltip ?: value
            css {
                props.style?.let { +it }
                props.color?.let { color = it }
                ellipsis()
                minWidth = 0.px
            }
            +props.text
        }

        span {
            className = COPY_ICON_CLASS
            title = "Copy"
            onClick = { event ->
                // The row underneath navigates on click — copying must not also open the job.
                event.stopPropagation()
                navigator.clipboard.writeTextAsync(value)
                copied = true
            }
            css {
                flexRow()
                flexShrink = number(0.0)
                cursor = Cursor.pointer
                border = None.none
                background = None.none
                padding = 0.px
                color = if (copied) SchedulerColors.primary else SchedulerColors.onSurfaceVariant
                // Always laid out, only painted on hover (or while showing the tick).
                opacity = if (copied) number(1.0) else number(0.0)
                asDynamic().transition = "opacity 0.12s ease-out"
            }
            if (copied) {
                CheckIcon { size = 14.px }
            } else {
                CopyIcon { size = 14.px }
            }
        }
    }
}

private const val COPIED_FEEDBACK_MS = 1200L
private val COPY_ICON_CLASS = web.cssom.ClassName("sch-copy-glyph")

/** `&:hover .sch-copy-glyph { … }` — Emotion has no typed descendant selector for a ClassName. */
private fun csstype.PropertiesBuilder.descendants(
    className: web.cssom.ClassName,
    block: csstype.PropertiesBuilder.() -> Unit,
) {
    ".$className"(block)
}
