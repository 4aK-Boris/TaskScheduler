package cs.trade.scheduler.core.frontend.ui

import csstype.PropertiesBuilder
import cs.trade.scheduler.core.frontend.theme.SchedulerColors
import cs.trade.scheduler.core.frontend.theme.SchedulerRadius
import cs.trade.scheduler.core.frontend.theme.SchedulerText
import emotion.react.css
import react.FC
import react.Key
import react.Props
import react.PropsWithChildren
import react.RefCallback
import react.dom.aria.AriaChecked
import react.dom.aria.AriaRole
import react.dom.html.ReactHTML.button
import react.dom.html.ReactHTML.input
import react.dom.html.ReactHTML.label
import react.dom.html.ReactHTML.option
import react.dom.html.ReactHTML.select
import react.dom.html.ReactHTML.span
import web.cssom.AlignItems
import web.cssom.Border
import web.cssom.Color
import web.cssom.Cursor
import web.cssom.Display
import web.cssom.JustifyContent
import web.cssom.Length
import web.cssom.LineStyle
import web.cssom.None
import web.cssom.number
import web.cssom.Padding
import web.cssom.TextTransform
import web.cssom.px
import web.html.ButtonType
import web.html.HTMLInputElement
import web.html.InputType
import web.html.button as buttonType
import web.html.checkbox as checkboxInput
import web.html.text as textInput

/**
 * The dashboard's control set. Deliberately small and hand-rolled on top of native elements
 * rather than pulled from a component library: the whole surface is buttons, inputs, chips and
 * tables, and owning them keeps the "Graphite" look exact and the JS payload minimal.
 *
 * Every control is a real `<button>` / `<input>`, so keyboard and screen-reader behaviour comes
 * for free — something the Compose canvas build could not offer at all.
 */

public enum class ButtonVariant {
    /** Cobalt fill — the single primary action on a screen. */
    FILLED,

    /** Hairline outline — secondary actions that still deserve a border. */
    OUTLINED,

    /** No chrome until hover — dense row actions, toolbar items. */
    TEXT,

    /** Red fill — destructive (delete, bulk delete). */
    DANGER,
}

public enum class ButtonSize { SMALL, MEDIUM }

external interface ButtonProps : PropsWithChildren {
    var variant: ButtonVariant?
    var size: ButtonSize?
    var disabled: Boolean?
    var onClick: (() -> Unit)?
    var title: String?
}

public val Button: FC<ButtonProps> = FC { props ->
    val variant = props.variant ?: ButtonVariant.OUTLINED
    val small = props.size == ButtonSize.SMALL
    val isDisabled = props.disabled == true

    button {
        type = ButtonType.buttonType
        disabled = isDisabled
        props.title?.let { title = it }
        onClick = { props.onClick?.invoke() }
        css {
            inlineRow(gap = 6.px, justify = JustifyContent.center)
            padding = if (small) Padding(4.px, 10.px) else Padding(7.px, 14.px)
            borderRadius = SchedulerRadius.small
            cursor = if (isDisabled) Cursor.notAllowed else Cursor.pointer
            whiteSpace = web.cssom.WhiteSpace.nowrap
            if (small) +SchedulerText.labelMedium else +SchedulerText.labelLarge
            asDynamic().transition = INTERACTIVE_TRANSITION
            variantStyle(variant, isDisabled)
        }
        +props.children
    }
}

/** Square icon-only button — nav toggles, row actions, copy buttons. */
external interface IconButtonProps : PropsWithChildren {
    var onClick: (() -> Unit)?
    var title: String?
    var disabled: Boolean?
    var size: Length?

    /** Render in the accent colour — used for the "active" state of a toggle. */
    var accented: Boolean?
}

public val IconButton: FC<IconButtonProps> = FC { props ->
    val edge = props.size ?: 32.px
    val isDisabled = props.disabled == true
    button {
        type = ButtonType.buttonType
        disabled = isDisabled
        props.title?.let { title = it }
        onClick = { props.onClick?.invoke() }
        css {
            inlineRow(justify = JustifyContent.center)
            width = edge
            height = edge
            padding = 0.px
            borderRadius = SchedulerRadius.small
            border = None.none
            backgroundColor = SchedulerColors.transparent
            color = if (props.accented == true) SchedulerColors.primary else SchedulerColors.onSurfaceVariant
            cursor = if (isDisabled) Cursor.notAllowed else Cursor.pointer
            asDynamic().transition = INTERACTIVE_TRANSITION
            if (isDisabled) {
                opacity = number(DISABLED_OPACITY)
            } else {
                hover {
                    backgroundColor = SchedulerColors.surfaceContainerHigh
                    color = SchedulerColors.onSurface
                }
            }
        }
        +props.children
    }
}

/** Single-line text field. Controlled — the caller owns the value. */
external interface TextInputProps : Props {
    var value: String
    var onValueChange: ((String) -> Unit)?
    var placeholder: String?
    var monospace: Boolean?
    var width: Length?
    var disabled: Boolean?

    /** Fired on Enter — the nav's job-id jump and the filter fields both submit this way. */
    var onSubmit: (() -> Unit)?
}

public val TextInput: FC<TextInputProps> = FC { props ->
    input {
        type = InputType.textInput
        value = props.value
        props.placeholder?.let { placeholder = it }
        disabled = props.disabled == true
        onChange = { event -> props.onValueChange?.invoke(event.target.value) }
        onKeyDown = { event ->
            if (event.key == "Enter") props.onSubmit?.invoke()
        }
        css {
            props.width?.let { width = it }
            padding = Padding(6.px, 10.px)
            borderRadius = SchedulerRadius.small
            hairline(SchedulerColors.outline)
            backgroundColor = SchedulerColors.surfaceContainerLowest
            color = SchedulerColors.onSurface
            if (props.monospace == true) +SchedulerText.mono else +SchedulerText.bodySmall
            asDynamic().transition = INTERACTIVE_TRANSITION
            focus {
                outline = None.none
                borderColor = SchedulerColors.primary
                boxShadow = web.cssom.BoxShadow(0.px, 0.px, 0.px, 2.px, SchedulerColors.primaryContainer)
            }
            placeholder {
                color = SchedulerColors.onSurfaceVariant
            }
        }
    }
}

/** Native dropdown, themed. Used for page size, state filter, sort direction. */
external interface SelectProps : Props {
    var value: String
    var options: List<SelectOption>
    var onValueChange: ((String) -> Unit)?
    var disabled: Boolean?
    var width: Length?
}

public data class SelectOption(val value: String, val label: String)

public val Select: FC<SelectProps> = FC { props ->
    select {
        value = props.value
        disabled = props.disabled == true
        onChange = { event -> props.onValueChange?.invoke(event.target.value) }
        css {
            props.width?.let { width = it }
            padding = Padding(6.px, 8.px)
            borderRadius = SchedulerRadius.small
            hairline(SchedulerColors.outline)
            backgroundColor = SchedulerColors.surfaceContainerLowest
            color = SchedulerColors.onSurface
            cursor = Cursor.pointer
            +SchedulerText.bodySmall
            focus {
                outline = None.none
                borderColor = SchedulerColors.primary
            }
        }
        props.options.forEach { opt ->
            option {
                key = Key(opt.value)
                value = opt.value
                +opt.label
            }
        }
    }
}

/** Checkbox with an optional label — bulk-select columns, "only failed" filters. */
external interface CheckboxProps : Props {
    var checked: Boolean
    var onCheckedChange: ((Boolean) -> Unit)?
    var label: String?
    var disabled: Boolean?

    /** Header checkbox state when only some rows are selected. */
    var indeterminate: Boolean?
}

public val Checkbox: FC<CheckboxProps> = FC { props ->
    label {
        css {
            inlineRow(gap = 6.px)
            cursor = if (props.disabled == true) Cursor.notAllowed else Cursor.pointer
            +SchedulerText.bodySmall
            color = SchedulerColors.onSurface
        }
        input {
            type = InputType.checkboxInput
            checked = props.checked
            disabled = props.disabled == true
            onChange = { event -> props.onCheckedChange?.invoke(event.target.checked) }
            // `indeterminate` is a DOM property with no HTML attribute, so React cannot set it
            // declaratively — a ref callback is the only way in.
            ref = RefCallback<HTMLInputElement> { element ->
                element.indeterminate = props.indeterminate == true
            }
            css {
                width = 14.px
                height = 14.px
                accentColor = SchedulerColors.primary
                cursor = Cursor.pointer
                margin = 0.px
            }
        }
        props.label?.let { +it }
    }
}

/**
 * On/off toggle for a persisted setting (a recurring definition's enabled flag). A real `<button
 * role="switch">` rather than a styled checkbox, so its pressed state is announced correctly.
 */
external interface SwitchProps : Props {
    var checked: Boolean
    var onCheckedChange: ((Boolean) -> Unit)?
    var disabled: Boolean?
    var title: String?
}

public val Switch: FC<SwitchProps> = FC { props ->
    val isDisabled = props.disabled == true
    button {
        type = ButtonType.buttonType
        role = AriaRole.switch
        ariaChecked = if (props.checked) AriaChecked.`true` else AriaChecked.`false`
        props.title?.let { title = it }
        disabled = isDisabled
        onClick = { props.onCheckedChange?.invoke(!props.checked) }
        css {
            inlineRow()
            width = 38.px
            height = 22.px
            padding = 2.px
            borderRadius = SchedulerRadius.pill
            border = None.none
            cursor = if (isDisabled) Cursor.notAllowed else Cursor.pointer
            backgroundColor = if (props.checked) SchedulerColors.primary else SchedulerColors.surfaceContainerHighest
            asDynamic().transition = INTERACTIVE_TRANSITION
            if (isDisabled) opacity = number(DISABLED_OPACITY)
        }
        span {
            css {
                width = 18.px
                height = 18.px
                borderRadius = SchedulerRadius.pill
                backgroundColor = if (props.checked) SchedulerColors.onPrimary else SchedulerColors.outline
                // Slide the knob rather than re-laying the row out — no reflow on toggle.
                asDynamic().transform = if (props.checked) "translateX(16px)" else "translateX(0)"
                asDynamic().transition = "transform 0.16s ease-out, background-color 0.16s ease-out"
            }
        }
    }
}

/**
 * Small status pill: a coloured container with matching text. The job-state chips, queue-health
 * badges and the connection indicator are all this shape.
 */
external interface ChipProps : PropsWithChildren {
    var container: Color
    var content: Color
    var uppercase: Boolean?
    var title: String?
}

public val Chip: FC<ChipProps> = FC { props ->
    span {
        props.title?.let { title = it }
        css {
            inlineRow(gap = 6.px)
            padding = Padding(3.px, 8.px)
            borderRadius = SchedulerRadius.small
            backgroundColor = props.container
            color = props.content
            +SchedulerText.labelSmall
            if (props.uppercase == true) textTransform = TextTransform.uppercase
            whiteSpace = web.cssom.WhiteSpace.nowrap
        }
        +props.children
    }
}

/**
 * Selectable filter pill — the state filter, dead-letter toggle and page-size picker.
 *
 * Distinct from [Chip], which is a passive status badge: this one is a real `<button>` with a
 * pressed state.
 */
external interface ToggleChipProps : PropsWithChildren {
    var selected: Boolean
    var onToggle: (() -> Unit)?
    var disabled: Boolean?
    var title: String?
}

public val ToggleChip: FC<ToggleChipProps> = FC { props ->
    val isDisabled = props.disabled == true
    button {
        type = ButtonType.buttonType
        disabled = isDisabled
        role = AriaRole.switch
        ariaChecked = if (props.selected) AriaChecked.`true` else AriaChecked.`false`
        props.title?.let { title = it }
        onClick = { props.onToggle?.invoke() }
        css {
            inlineRow(gap = 6.px)
            padding = Padding(4.px, 10.px)
            borderRadius = SchedulerRadius.small
            cursor = if (isDisabled) Cursor.notAllowed else Cursor.pointer
            +SchedulerText.labelMedium
            whiteSpace = web.cssom.WhiteSpace.nowrap
            asDynamic().transition = INTERACTIVE_TRANSITION
            if (props.selected) {
                backgroundColor = SchedulerColors.primaryContainer
                color = SchedulerColors.onPrimaryContainer
                border = Border(1.px, LineStyle.solid, SchedulerColors.primary)
            } else {
                backgroundColor = SchedulerColors.transparent
                color = SchedulerColors.onSurfaceVariant
                border = Border(1.px, LineStyle.solid, SchedulerColors.outlineVariant)
                if (!isDisabled) hover {
                    backgroundColor = SchedulerColors.surfaceContainerHigh
                    color = SchedulerColors.onSurface
                }
            }
            if (isDisabled) opacity = number(DISABLED_OPACITY)
        }
        +props.children
    }
}

/** A small filled circle — the dot inside connection / health chips. */
external interface DotProps : Props {
    var color: Color
    var size: Length?

    /** Breathe while a transient state is in progress ("reconnecting"). */
    var pulsing: Boolean?
}

public val Dot: FC<DotProps> = FC { props ->
    val edge = props.size ?: 8.px
    span {
        css {
            width = edge
            height = edge
            borderRadius = SchedulerRadius.pill
            backgroundColor = props.color
            flexShrink = number(0.0)
            if (props.pulsing == true) asDynamic().animation = "sch-pulse 1.5s ease-in-out infinite"
        }
    }
}

private const val DISABLED_OPACITY = 0.45

/** Shared hover/focus easing. Written as the CSS shorthand — cssom exposes no Transition factory. */
private const val INTERACTIVE_TRANSITION = "all 0.14s ease-out"

/** Per-variant fills, borders and hover treatment. Split out so [Button] stays readable. */
private fun PropertiesBuilder.variantStyle(variant: ButtonVariant, isDisabled: Boolean) {
    when (variant) {
        ButtonVariant.FILLED -> {
            backgroundColor = SchedulerColors.primary
            color = SchedulerColors.onPrimary
            border = None.none
            if (!isDisabled) hover { backgroundColor = SchedulerColors.inversePrimary }
        }

        ButtonVariant.OUTLINED -> {
            backgroundColor = SchedulerColors.surfaceContainerLowest
            color = SchedulerColors.onSurface
            border = Border(1.px, LineStyle.solid, SchedulerColors.outline)
            if (!isDisabled) hover {
                backgroundColor = SchedulerColors.surfaceContainerHigh
                borderColor = SchedulerColors.primary
            }
        }

        ButtonVariant.TEXT -> {
            backgroundColor = SchedulerColors.transparent
            color = SchedulerColors.primary
            border = None.none
            if (!isDisabled) hover { backgroundColor = SchedulerColors.primaryContainer }
        }

        ButtonVariant.DANGER -> {
            backgroundColor = SchedulerColors.errorContainer
            color = SchedulerColors.onErrorContainer
            border = None.none
            if (!isDisabled) hover { backgroundColor = SchedulerColors.error; color = SchedulerColors.onError }
        }
    }
    if (isDisabled) opacity = number(DISABLED_OPACITY)
}

/** Like [flexRow] but `inline-flex` — controls sit in text flow, not as block-level boxes. */
private fun PropertiesBuilder.inlineRow(
    gap: Length? = null,
    justify: JustifyContent? = null,
) {
    display = Display.inlineFlex
    alignItems = AlignItems.center
    justify?.let { justifyContent = it }
    gap?.let { this.gap = it }
}
