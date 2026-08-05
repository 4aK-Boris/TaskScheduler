package cs.trade.scheduler.core.frontend.theme

import csstype.PropertiesBuilder
import emotion.react.Global
import emotion.react.styles
import react.FC
import react.Props
import web.cssom.None
import web.cssom.pct
import web.cssom.px
import web.dom.document

/**
 * Emits the design system's CSS custom properties (both palettes) plus the document-level
 * defaults. Mount ONCE at the root of the tree, above everything else.
 *
 * The two palettes are always both present in the stylesheet; which one wins is decided by the
 * `data-theme` attribute on `<html>` (see [applyThemeMode]). That means toggling dark mode
 * repaints via CSS alone — no React re-render, no flash of restyled content.
 *
 * `@font-face` and the pre-JS reset deliberately live in `index.html` instead: the browser must
 * start fetching IBM Plex and paint the right background colour before this bundle even parses.
 */
public val SchedulerGlobalStyles: FC<Props> = FC {
    Global {
        styles {
            ":root" {
                paletteVariables(LightPalette)
            }
            // Explicit `light` keeps the toggle symmetric — the attribute is always written, so
            // neither direction depends on attribute-absence.
            "[data-theme='light']" {
                paletteVariables(LightPalette)
            }
            "[data-theme='dark']" {
                paletteVariables(DarkPalette)
            }

            "body" {
                margin = 0.px
                backgroundColor = SchedulerColors.background
                color = SchedulerColors.onSurface
                fontFamily = SANS_STACK
                +SchedulerText.bodyMedium
                // Skiko rendered its own text; the DOM needs these to match the crispness the
                // dashboard had on canvas.
                asDynamic()["-webkit-font-smoothing"] = "antialiased"
                asDynamic()["text-rendering"] = "optimizeLegibility"
            }

            "*, *::before, *::after" {
                asDynamic()["box-sizing"] = "border-box"
            }

            // Buttons/inputs don't inherit the body font by default — every control in the UI kit
            // would otherwise fall back to the UA's default sans.
            "button, input, select, textarea" {
                fontFamily = SANS_STACK
                fontSize = 100.pct
                color = SchedulerColors.onSurface
            }

            // One shared spin animation for every Spinner instance — declaring the keyframes
            // inside the component would make Emotion inject a duplicate rule per mount.
            "@keyframes sch-spin" {
                asDynamic()["from"] = jsObject("transform" to "rotate(0deg)")
                asDynamic()["to"] = jsObject("transform" to "rotate(360deg)")
            }

            // Breathing dot for transient states ("reconnecting") — signals "working on it"
            // instead of a frozen pill.
            "@keyframes sch-pulse" {
                asDynamic()["0%, 100%"] = jsObject("opacity" to "0.35")
                asDynamic()["50%"] = jsObject("opacity" to "1")
            }

            // Screen transition. The wasm build cross-faded via Decompose's stack animation;
            // in the DOM the incoming screen simply fades up as it mounts.
            "@keyframes sch-fade-in" {
                asDynamic()["from"] = jsObject("opacity" to "0")
                asDynamic()["to"] = jsObject("opacity" to "1")
            }

            // Dense operator tables scroll a lot; the default chrome scrollbar is a light-mode
            // artefact that stays pale on the dark canvas.
            "::-webkit-scrollbar" {
                width = 10.px
                height = 10.px
            }
            "::-webkit-scrollbar-track" {
                backgroundColor = SchedulerColors.transparent
            }
            "::-webkit-scrollbar-thumb" {
                backgroundColor = SchedulerColors.outlineVariant
                borderRadius = SchedulerRadius.pill
                border = None.none
            }
            "::-webkit-scrollbar-thumb:hover" {
                backgroundColor = SchedulerColors.outline
            }
        }
    }
}

/**
 * Point the whole document at one palette. Called by the root component whenever the persisted
 * dark-mode preference changes (and once at startup before the first paint).
 */
public fun applyThemeMode(isDark: Boolean) {
    document.documentElement.setAttribute("data-theme", if (isDark) "dark" else "light")
}

/**
 * Write one palette out as `--sch-*` declarations. Custom properties have no typed slot in the
 * cssom `Properties` interface — they are set dynamically, which is exactly what Emotion expects
 * to receive in the style object.
 */
private fun PropertiesBuilder.paletteVariables(palette: SchedulerPalette) {
    for ((role, value) in palette.asVariables()) {
        asDynamic()["--sch-$role"] = value
    }
}

/** Build a plain JS object from pairs — for the untyped corners of the style DSL (keyframes). */
private fun jsObject(vararg entries: Pair<String, String>): Any {
    val result = js("({})")
    for ((key, value) in entries) {
        result[key] = value
    }
    return result
}
