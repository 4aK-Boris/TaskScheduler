package cs.trade.scheduler.core.frontend.ui

import emotion.react.css
import react.ChildrenBuilder
import react.FC
import react.Props
import react.dom.svg.ReactSVG.circle
import react.dom.svg.ReactSVG.line
import react.dom.svg.ReactSVG.path
import react.dom.svg.ReactSVG.polyline
import react.dom.svg.ReactSVG.rect
import react.dom.svg.ReactSVG.svg
import react.dom.svg.StrokeLinecap
import react.dom.svg.StrokeLinejoin
import web.cssom.Length
import web.cssom.number
import web.cssom.px

/**
 * The dashboard's icon set — hand-drawn inline SVG on a 24×24 grid, stroked with `currentColor`.
 *
 * These replace the Compose `Canvas` drawings the wasm build used. Inline SVG is the better fit
 * for the DOM: it inherits colour from the surrounding text (so a hover state needs no icon-aware
 * code), stays crisp at any zoom, and adds nothing to the bundle — no icon font, no sprite sheet,
 * no dependency.
 */
external interface IconProps : Props {
    /** Edge length of the square icon box. Defaults to 16px — the size used in dense tables. */
    var size: Length?
}

/** Magnifier — the nav's "find job by id" field. */
public val SearchIcon: FC<IconProps> = FC { props ->
    strokeIcon(props) {
        circle {
            cx = 11.0
            cy = 11.0
            r = 6.5
        }
        line {
            x1 = 16.0
            y1 = 16.0
            x2 = 21.0
            y2 = 21.0
        }
    }
}

/** Sun — shown while dark mode is ON (click to lighten). */
public val SunIcon: FC<IconProps> = FC { props ->
    strokeIcon(props) {
        circle {
            cx = 12.0
            cy = 12.0
            r = 4.0
        }
        // Eight rays as explicit lines rather than a dasharray circle — each stays perfectly
        // centred at 16px, which the dash approach does not.
        for (ray in RAYS) {
            line {
                x1 = ray.x1
                y1 = ray.y1
                x2 = ray.x2
                y2 = ray.y2
            }
        }
    }
}

/** Crescent moon — shown while light mode is ON (click to darken). */
public val MoonIcon: FC<IconProps> = FC { props ->
    strokeIcon(props) {
        path {
            d = "M20 14.5A8.5 8.5 0 0 1 9.5 4a7 7 0 1 0 10.5 10.5Z"
        }
    }
}

/** Two offset sheets — "copy to clipboard". */
public val CopyIcon: FC<IconProps> = FC { props ->
    strokeIcon(props) {
        rect {
            x = 9.0
            y = 9.0
            width = 11.0
            height = 11.0
            rx = 2.0
        }
        path {
            d = "M5 15H4a1 1 0 0 1-1-1V4a1 1 0 0 1 1-1h10a1 1 0 0 1 1 1v1"
        }
    }
}

/** Tick — copy confirmation, "selected" states. */
public val CheckIcon: FC<IconProps> = FC { props ->
    strokeIcon(props) {
        polyline {
            points = "4,12.5 9.5,18 20,6.5"
        }
    }
}

public val CloseIcon: FC<IconProps> = FC { props ->
    strokeIcon(props) {
        line {
            x1 = 5.0
            y1 = 5.0
            x2 = 19.0
            y2 = 19.0
        }
        line {
            x1 = 19.0
            y1 = 5.0
            x2 = 5.0
            y2 = 19.0
        }
    }
}

public val ChevronDownIcon: FC<IconProps> = FC { props ->
    strokeIcon(props) {
        polyline {
            points = "5,9 12,16 19,9"
        }
    }
}

public val ChevronUpIcon: FC<IconProps> = FC { props ->
    strokeIcon(props) {
        polyline {
            points = "5,15 12,8 19,15"
        }
    }
}

public val ChevronLeftIcon: FC<IconProps> = FC { props ->
    strokeIcon(props) {
        polyline {
            points = "15,5 8,12 15,19"
        }
    }
}

public val ChevronRightIcon: FC<IconProps> = FC { props ->
    strokeIcon(props) {
        polyline {
            points = "9,5 16,12 9,19"
        }
    }
}

/** Circular arrow — retry / rerun / refresh. */
public val RefreshIcon: FC<IconProps> = FC { props ->
    strokeIcon(props) {
        path {
            d = "M20 12a8 8 0 1 1-2.6-5.9"
        }
        polyline {
            points = "20,3 20,7.5 15.5,7.5"
        }
    }
}

/** Filled triangle — "run now" on recurring jobs. */
public val PlayIcon: FC<IconProps> = FC { props ->
    filledIcon(props) {
        path {
            d = "M8 5.5v13l11-6.5Z"
        }
    }
}

/** Two bars — a paused job type / queue. */
public val PauseIcon: FC<IconProps> = FC { props ->
    filledIcon(props) {
        path {
            d = "M8 5h3v14H8zM13 5h3v14h-3z"
        }
    }
}

/** Bin — delete. */
public val TrashIcon: FC<IconProps> = FC { props ->
    strokeIcon(props) {
        polyline {
            points = "4,6.5 20,6.5"
        }
        path {
            d = "M9.5 6.5V4.5h5v2M6.5 6.5l1 13h9l1-13"
        }
    }
}

/** Circle with a slash — cancel. */
public val CancelIcon: FC<IconProps> = FC { props ->
    strokeIcon(props) {
        circle {
            cx = 12.0
            cy = 12.0
            r = 8.5
        }
        line {
            x1 = 6.5
            y1 = 17.5
            x2 = 17.5
            y2 = 6.5
        }
    }
}

/** Downward arrow into a tray — export / download. */
public val DownloadIcon: FC<IconProps> = FC { props ->
    strokeIcon(props) {
        line {
            x1 = 12.0
            y1 = 4.0
            x2 = 12.0
            y2 = 15.0
        }
        polyline {
            points = "7,10.5 12,15.5 17,10.5"
        }
        path {
            d = "M4.5 18.5h15"
        }
    }
}

/**
 * Three sliders — the settings trigger. A "tune" glyph rather than a gear: the menu behind it
 * adjusts display preferences (refresh cadence, time format), it is not app configuration.
 */
public val TuneIcon: FC<IconProps> = FC { props ->
    strokeIcon(props) {
        for (slider in SLIDERS) {
            line {
                x1 = 4.0
                y1 = slider.y
                x2 = 20.0
                y2 = slider.y
            }
            circle {
                cx = slider.knobX
                cy = slider.y
                r = 2.2
                fill = "currentColor"
            }
        }
    }
}

private class Slider(val y: Double, val knobX: Double)

private val SLIDERS: List<Slider> = listOf(
    Slider(y = 7.0, knobX = 15.0),
    Slider(y = 12.0, knobX = 9.0),
    Slider(y = 17.0, knobX = 14.0),
)

/** Cobalt tile + white clock — the product mark. Matches the page favicon. */
public val BrandMark: FC<IconProps> = FC { props ->
    val edge = props.size ?: 26.px
    svg {
        viewBox = "0 0 32 32"
        css {
            width = edge
            height = edge
            flexShrink = number(0.0)
        }
        rect {
            width = 32.0
            height = 32.0
            rx = 7.0
            fill = COBALT
        }
        circle {
            cx = 16.0
            cy = 16.0
            r = 8.5
            fill = "none"
            stroke = ON_COBALT
            strokeWidth = 2.5
        }
        path {
            d = "M16 11V16l3.5 2.5"
            fill = "none"
            stroke = ON_COBALT
            strokeWidth = 2.5
            strokeLinecap = StrokeLinecap.round
            strokeLinejoin = StrokeLinejoin.round
        }
    }
}

// The mark keeps its own cobalt in BOTH palettes — a brand tile that inverts with the theme stops
// reading as a logo. Every other icon inherits `currentColor` from its surroundings instead.
private const val COBALT = "#2348E0"
private const val ON_COBALT = "#FFFFFF"

private class Ray(val x1: Double, val y1: Double, val x2: Double, val y2: Double)

private val RAYS: List<Ray> = listOf(
    Ray(12.0, 1.5, 12.0, 4.0),
    Ray(12.0, 20.0, 12.0, 22.5),
    Ray(1.5, 12.0, 4.0, 12.0),
    Ray(20.0, 12.0, 22.5, 12.0),
    Ray(4.6, 4.6, 6.4, 6.4),
    Ray(17.6, 17.6, 19.4, 19.4),
    Ray(4.6, 19.4, 6.4, 17.6),
    Ray(17.6, 6.4, 19.4, 4.6),
)

private const val DEFAULT_STROKE = 1.8

/** Outline glyph: no fill, `currentColor` stroke with round joins. */
private fun ChildrenBuilder.strokeIcon(
    props: IconProps,
    content: ChildrenBuilder.() -> Unit,
) {
    svg {
        viewBox = "0 0 24 24"
        fill = "none"
        stroke = "currentColor"
        strokeWidth = DEFAULT_STROKE
        strokeLinecap = StrokeLinecap.round
        strokeLinejoin = StrokeLinejoin.round
        css {
            width = props.size ?: DEFAULT_SIZE
            height = props.size ?: DEFAULT_SIZE
            flexShrink = number(0.0)
        }
        content()
    }
}

/** Solid glyph: filled with `currentColor`, no stroke. For small dense shapes (play, pause). */
private fun ChildrenBuilder.filledIcon(
    props: IconProps,
    content: ChildrenBuilder.() -> Unit,
) {
    svg {
        viewBox = "0 0 24 24"
        fill = "currentColor"
        stroke = "none"
        css {
            width = props.size ?: DEFAULT_SIZE
            height = props.size ?: DEFAULT_SIZE
            flexShrink = number(0.0)
        }
        content()
    }
}

private val DEFAULT_SIZE: Length = 16.px
