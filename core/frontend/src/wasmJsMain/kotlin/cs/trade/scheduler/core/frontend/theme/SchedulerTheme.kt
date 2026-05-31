package cs.trade.scheduler.core.frontend.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import cs.trade.scheduler.core.frontend.generated.resources.Res
import cs.trade.scheduler.core.frontend.generated.resources.ibm_plex_sans_bold
import cs.trade.scheduler.core.frontend.generated.resources.ibm_plex_sans_medium
import cs.trade.scheduler.core.frontend.generated.resources.ibm_plex_sans_regular
import cs.trade.scheduler.core.frontend.generated.resources.ibm_plex_sans_semibold
import org.jetbrains.compose.resources.Font

/**
 * Material 3 theme for the dashboard. A deliberate design system, not the M3 baseline:
 *  - "Graphite" brand colour scheme (cobalt on cool neutrals) — [SchedulerColorScheme].
 *  - IBM Plex Sans across the whole type scale (bundled font, NOT the skiko-default Roboto —
 *    that default was the "Google" tell) — [SchedulerTypography] + [withFontFamily].
 *  - Hard, near-square radii — [SchedulerShapes].
 *  - Semantic success/warning/info via `MaterialTheme.schedulerColors`.
 *
 * [isDark] is driven by the root component (persisted to localStorage, DESIGN.md 15.4).
 */
@Composable
public fun SchedulerTheme(
    isDark: Boolean,
    content: @Composable () -> Unit,
) {
    val colorScheme = if (isDark) SchedulerDarkColors else SchedulerLightColors
    val semanticColors = if (isDark) SchedulerDarkSemanticColors else SchedulerLightSemanticColors
    CompositionLocalProvider(LocalSchedulerSemanticColors provides semanticColors) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = schedulerTypography(),
            shapes = SchedulerShapes,
            content = content,
        )
    }
}

// Built in composition because compose-resources `Font(...)` is a @Composable that loads the
// font bytes asynchronously on wasm (falls back to the platform sans for the first frame, then
// recomposes once IBM Plex is decoded). The tuned scale ([SchedulerTypography]) supplies the
// weights/tracking; this just swaps the family onto every role.
@Composable
private fun schedulerTypography(): Typography {
    val plexSans = FontFamily(
        Font(Res.font.ibm_plex_sans_regular, FontWeight.Normal),
        Font(Res.font.ibm_plex_sans_medium, FontWeight.Medium),
        Font(Res.font.ibm_plex_sans_semibold, FontWeight.SemiBold),
        Font(Res.font.ibm_plex_sans_bold, FontWeight.Bold),
    )
    return SchedulerTypography.withFontFamily(plexSans)
}
