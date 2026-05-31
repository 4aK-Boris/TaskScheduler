package cs.trade.scheduler.core.frontend.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

// Deliberate type scale on the platform sans (no bundled brand font yet — that's a follow-up:
// a .ttf in resources + FontFamily, which is async on wasm). Firmer title weights and tighter
// tracking than the M3 baseline so dense operator tables and nav read crisply. Only the roles
// the dashboard actually uses are tuned; everything else inherits the M3 default.
internal val SchedulerTypography: Typography = Typography().let { d ->
    d.copy(
        titleLarge = d.titleLarge.copy(fontWeight = FontWeight.SemiBold, letterSpacing = (-0.2).sp),
        titleMedium = d.titleMedium.copy(fontWeight = FontWeight.SemiBold),
        titleSmall = d.titleSmall.copy(fontWeight = FontWeight.SemiBold),
        labelLarge = d.labelLarge.copy(fontWeight = FontWeight.Medium),
        labelMedium = d.labelMedium.copy(fontWeight = FontWeight.Medium, letterSpacing = 0.3.sp),
        labelSmall = d.labelSmall.copy(fontWeight = FontWeight.SemiBold, letterSpacing = 0.4.sp),
    )
}

/** Apply one [FontFamily] to every role of this [Typography] — used to swap in IBM Plex Sans. */
internal fun Typography.withFontFamily(family: FontFamily): Typography = copy(
    displayLarge = displayLarge.copy(fontFamily = family),
    displayMedium = displayMedium.copy(fontFamily = family),
    displaySmall = displaySmall.copy(fontFamily = family),
    headlineLarge = headlineLarge.copy(fontFamily = family),
    headlineMedium = headlineMedium.copy(fontFamily = family),
    headlineSmall = headlineSmall.copy(fontFamily = family),
    titleLarge = titleLarge.copy(fontFamily = family),
    titleMedium = titleMedium.copy(fontFamily = family),
    titleSmall = titleSmall.copy(fontFamily = family),
    bodyLarge = bodyLarge.copy(fontFamily = family),
    bodyMedium = bodyMedium.copy(fontFamily = family),
    bodySmall = bodySmall.copy(fontFamily = family),
    labelLarge = labelLarge.copy(fontFamily = family),
    labelMedium = labelMedium.copy(fontFamily = family),
    labelSmall = labelSmall.copy(fontFamily = family),
)
