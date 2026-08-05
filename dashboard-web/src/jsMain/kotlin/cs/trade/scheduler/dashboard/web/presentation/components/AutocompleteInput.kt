package cs.trade.scheduler.dashboard.web.presentation.components

import cs.trade.scheduler.core.frontend.theme.SchedulerColors
import cs.trade.scheduler.core.frontend.theme.SchedulerRadius
import cs.trade.scheduler.core.frontend.theme.SchedulerText
import cs.trade.scheduler.core.frontend.ui.TextInput
import cs.trade.scheduler.core.frontend.ui.flexRow
import cs.trade.scheduler.core.frontend.ui.hairline
import emotion.react.css
import react.FC
import react.Key
import react.Props
import react.ReactNode
import react.dom.html.ReactHTML.div
import react.dom.html.ReactHTML.span
import react.useMemo
import react.useState
import web.cssom.Auto
import web.cssom.BoxShadow
import web.cssom.Cursor
import web.cssom.Length
import web.cssom.Overflow
import web.cssom.Padding
import web.cssom.Position
import web.cssom.integer
import web.cssom.number
import web.cssom.pct
import web.cssom.px

/**
 * Free-text field with a dropdown of known values, filtered by what's typed. Shared by the Jobs
 * queue / task-type filters and the Types pause composer.
 *
 * Free text always wins: an operator can filter on (or pause) a value the server has never seen,
 * so the suggestions are a shortcut, never a constraint.
 *
 * Dismissal is a transparent backdrop rather than a blur handler — blur fires before the click on
 * a suggestion is delivered and would swallow the pick.
 */
external interface AutocompleteInputProps : Props {
    var value: String
    var placeholder: String?
    var suggestions: List<String>
    var onValueChange: (String) -> Unit
    var width: Length?
    var monospace: Boolean?
    var disabled: Boolean?
    var onSubmit: (() -> Unit)?

    /** Optional trailing decoration per suggestion — Types uses it for the PAUSED badge. */
    var suggestionBadge: ((String) -> ReactNode?)?
}

public val AutocompleteInput: FC<AutocompleteInputProps> = FC { props ->
    var open by useState(false)

    val matches = useMemo(props.value, props.suggestions) {
        val needle = props.value.trim()
        props.suggestions
            .filter { needle.isEmpty() || it.contains(needle, ignoreCase = true) }
            .take(MAX_SUGGESTIONS)
    }
    val showSuggestions = open && matches.isNotEmpty() && props.disabled != true

    div {
        css {
            position = Position.relative
            props.width?.let { width = it } ?: run { flexGrow = number(1.0) }
        }

        TextInput {
            value = props.value
            props.placeholder?.let { placeholder = it }
            monospace = props.monospace
            disabled = props.disabled
            width = 100.pct
            onValueChange = { next ->
                props.onValueChange(next)
                open = true
            }
            onSubmit = {
                open = false
                props.onSubmit?.invoke()
            }
        }

        if (showSuggestions) {
            div {
                onClick = { open = false }
                css {
                    position = Position.fixed
                    inset = 0.px
                    zIndex = integer(40)
                }
            }
            div {
                css {
                    position = Position.absolute
                    top = 38.px
                    left = 0.px
                    right = 0.px
                    zIndex = integer(50)
                    maxHeight = 280.px
                    overflowY = Auto.auto
                    backgroundColor = SchedulerColors.surface
                    borderRadius = SchedulerRadius.medium
                    hairline()
                    boxShadow = BoxShadow(0.px, 6.px, 20.px, SchedulerColors.shadow)
                }
                matches.forEach { match ->
                    div {
                        key = Key(match)
                        onClick = {
                            props.onValueChange(match)
                            open = false
                        }
                        css {
                            flexRow(gap = 8.px)
                            padding = Padding(7.px, 12.px)
                            cursor = Cursor.pointer
                            overflow = Overflow.hidden
                            hover { backgroundColor = SchedulerColors.surfaceContainerLow }
                        }
                        span {
                            css {
                                if (props.monospace == true) +SchedulerText.mono
                                overflow = Overflow.hidden
                                textOverflow = web.cssom.TextOverflow.ellipsis
                                whiteSpace = web.cssom.WhiteSpace.nowrap
                            }
                            +match
                        }
                        props.suggestionBadge?.invoke(match)?.let { +it }
                    }
                }
            }
        }
    }
}

private const val MAX_SUGGESTIONS = 30
