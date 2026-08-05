package cs.trade.scheduler.core.frontend.theme

import csstype.Properties
import js.objects.unsafeJso
import web.cssom.Color
import web.cssom.FontFamily
import web.cssom.Length
import web.cssom.integer
import web.cssom.px
import web.cssom.string

/**
 * Design tokens for the dashboard. Three objects, used directly inside Emotion `css { }` blocks:
 *
 * ```
 * css {
 *     backgroundColor = SchedulerColors.surface
 *     borderRadius = SchedulerRadius.medium
 *     +SchedulerText.titleMedium
 * }
 * ```
 *
 * Every colour resolves to `var(--sch-*)` rather than a literal — [SchedulerGlobalStyles] defines
 * those variables for both palettes, so flipping dark mode is one attribute write on `<html>`
 * (see [applyThemeMode]) and costs no React re-render.
 */
public object SchedulerColors {
    public val primary: Color = themeColor("primary")
    public val onPrimary: Color = themeColor("on-primary")
    public val primaryContainer: Color = themeColor("primary-container")
    public val onPrimaryContainer: Color = themeColor("on-primary-container")

    public val secondary: Color = themeColor("secondary")
    public val onSecondary: Color = themeColor("on-secondary")
    public val secondaryContainer: Color = themeColor("secondary-container")
    public val onSecondaryContainer: Color = themeColor("on-secondary-container")

    public val tertiary: Color = themeColor("tertiary")
    public val onTertiary: Color = themeColor("on-tertiary")
    public val tertiaryContainer: Color = themeColor("tertiary-container")
    public val onTertiaryContainer: Color = themeColor("on-tertiary-container")

    public val error: Color = themeColor("error")
    public val onError: Color = themeColor("on-error")
    public val errorContainer: Color = themeColor("error-container")
    public val onErrorContainer: Color = themeColor("on-error-container")

    public val background: Color = themeColor("background")
    public val onBackground: Color = themeColor("on-background")
    public val surface: Color = themeColor("surface")
    public val onSurface: Color = themeColor("on-surface")
    public val surfaceVariant: Color = themeColor("surface-variant")
    public val onSurfaceVariant: Color = themeColor("on-surface-variant")

    public val surfaceContainerLowest: Color = themeColor("surface-container-lowest")
    public val surfaceContainerLow: Color = themeColor("surface-container-low")
    public val surfaceContainer: Color = themeColor("surface-container")
    public val surfaceContainerHigh: Color = themeColor("surface-container-high")
    public val surfaceContainerHighest: Color = themeColor("surface-container-highest")

    public val outline: Color = themeColor("outline")
    public val outlineVariant: Color = themeColor("outline-variant")
    public val inverseSurface: Color = themeColor("inverse-surface")
    public val inverseOnSurface: Color = themeColor("inverse-on-surface")
    public val inversePrimary: Color = themeColor("inverse-primary")

    public val success: Color = themeColor("success")
    public val onSuccess: Color = themeColor("on-success")
    public val successContainer: Color = themeColor("success-container")
    public val onSuccessContainer: Color = themeColor("on-success-container")

    public val warning: Color = themeColor("warning")
    public val onWarning: Color = themeColor("on-warning")
    public val warningContainer: Color = themeColor("warning-container")
    public val onWarningContainer: Color = themeColor("on-warning-container")

    public val info: Color = themeColor("info")
    public val onInfo: Color = themeColor("on-info")
    public val infoContainer: Color = themeColor("info-container")
    public val onInfoContainer: Color = themeColor("on-info-container")

    public val shadow: Color = themeColor("shadow")

    /** Fully transparent — for "same shape, no fill" cases (ghost buttons, placeholder ticks). */
    public val transparent: Color = Color.transparent
}

/**
 * Type scale on IBM Plex Sans. Firmer title weights and tighter tracking than a stock Material
 * scale so dense operator tables and nav read crisply.
 *
 * Every role sits **two points above** the equivalent Material size: the wasm build inherited
 * Material's mobile-first scale, which read cramped on the desktop screens this dashboard actually
 * runs on — 12px body text in a table an operator scans all day is too small. Line heights moved
 * with the sizes to keep the rhythm.
 *
 * Each role is a bag of CSS properties applied with `+`, which merges it into the surrounding
 * `css { }` block — put it FIRST if the block then overrides a single property (e.g. colour).
 */
public object SchedulerText {
    public val displaySmall: Properties = textStyle(38.px, 46.px, 400)
    public val headlineMedium: Properties = textStyle(30.px, 38.px, 400)
    public val headlineSmall: Properties = textStyle(26.px, 34.px, 400)

    public val titleLarge: Properties = textStyle(24.px, 32.px, 600, (-0.2).px)
    public val titleMedium: Properties = textStyle(18.px, 26.px, 600, 0.15.px)
    public val titleSmall: Properties = textStyle(16.px, 22.px, 600, 0.1.px)

    public val bodyLarge: Properties = textStyle(18.px, 26.px, 400, 0.5.px)
    public val bodyMedium: Properties = textStyle(16.px, 24.px, 400, 0.25.px)
    public val bodySmall: Properties = textStyle(14.px, 20.px, 400, 0.4.px)

    public val labelLarge: Properties = textStyle(16.px, 22.px, 500, 0.1.px)
    public val labelMedium: Properties = textStyle(14.px, 20.px, 500, 0.3.px)
    public val labelSmall: Properties = textStyle(13.px, 18.px, 600, 0.4.px)

    /** Monospace slot — job ids, payload JSON, stack traces. Same size as [bodySmall]. */
    public val mono: Properties = unsafeJso {
        fontFamily = MONO_STACK
        fontSize = 14.px
        lineHeight = 20.px
        letterSpacing = 0.px
    }
}

/** Hard, near-square radii — a precision-instrument feel, not a rounded consumer app. */
public object SchedulerRadius {
    public val extraSmall: Length = 2.px
    public val small: Length = 4.px
    public val medium: Length = 6.px
    public val large: Length = 10.px
    public val pill: Length = 999.px
}

/**
 * Font stacks. IBM Plex Sans is served by the SPA (`fonts/IBMPlexSans-*.ttf`, declared as
 * `@font-face` in [SchedulerGlobalStyles]); the system fallbacks cover the first paint before
 * the file lands and any environment where it 404s.
 */
internal const val SANS_STACK_VALUE: String =
    "'IBM Plex Sans', system-ui, -apple-system, 'Segoe UI', Roboto, sans-serif"

internal const val MONO_STACK_VALUE: String =
    "'IBM Plex Mono', ui-monospace, SFMono-Regular, Menlo, Consolas, monospace"

// `string(...)` is the cssom escape hatch for a literal value — the only way to express a
// multi-family fallback stack, which the generated `FontFamily` constants can't represent.
public val SANS_STACK: FontFamily = string(SANS_STACK_VALUE)
public val MONO_STACK: FontFamily = string(MONO_STACK_VALUE)

private fun themeColor(role: String): Color = Color("var(--sch-$role)")

private fun textStyle(
    size: Length,
    height: Length,
    weight: Int,
    tracking: Length = 0.px,
): Properties = unsafeJso {
    fontSize = size
    lineHeight = height
    fontWeight = integer(weight)
    letterSpacing = tracking
}
