package cs.trade.scheduler.core.frontend.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

// "Graphite" — an industrial / engineering palette: cool-grey base, white elevated panels,
// one dominant cobalt accent, crisp hairline outlines. Deliberately NOT the M3 baseline
// (lavender) nor a candy multi-hue scheme — dominant colour + sharp accent reads as a precise,
// expensive tool. State hues (teal / amber) are desaturated so cobalt stays the lead.

internal val SchedulerLightColors = lightColorScheme(
    primary = Color(0xFF2348E0),            // cobalt
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFDBE2FF),
    onPrimaryContainer = Color(0xFF001550),
    secondary = Color(0xFF2F6F5E),          // muted teal — PROCESSING
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFCDE9DF),
    onSecondaryContainer = Color(0xFF07271E),
    tertiary = Color(0xFF8A5A12),           // bronze/amber — SCHEDULED
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFF5E2C4),
    onTertiaryContainer = Color(0xFF2A1A00),
    error = Color(0xFFC8102E),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFFD9DA),
    onErrorContainer = Color(0xFF40000A),
    background = Color(0xFFF1F3F5),         // cool grey canvas
    onBackground = Color(0xFF1A1C20),       // graphite ink
    surface = Color(0xFFFFFFFF),            // white elevated panels pop on the grey
    onSurface = Color(0xFF1A1C20),
    surfaceVariant = Color(0xFFE4E7EB),
    onSurfaceVariant = Color(0xFF565B62),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF7F8FA),
    surfaceContainer = Color(0xFFEEF0F3),
    surfaceContainerHigh = Color(0xFFE8EBEE),
    surfaceContainerHighest = Color(0xFFE2E5E9),
    outline = Color(0xFFB9BEC5),            // hairline borders
    outlineVariant = Color(0xFFD6DADF),
    inverseSurface = Color(0xFF2A2D32),
    inverseOnSurface = Color(0xFFF0F1F4),
    inversePrimary = Color(0xFFB4C4FF),
)

internal val SchedulerDarkColors = darkColorScheme(
    primary = Color(0xFFAEC0FF),
    onPrimary = Color(0xFF002C86),
    primaryContainer = Color(0xFF1A3BC0),
    onPrimaryContainer = Color(0xFFDBE2FF),
    secondary = Color(0xFF9FD0C0),
    onSecondary = Color(0xFF073529),
    secondaryContainer = Color(0xFF1E4D40),
    onSecondaryContainer = Color(0xFFCDE9DF),
    tertiary = Color(0xFFE8B873),
    onTertiary = Color(0xFF3F2A00),
    tertiaryContainer = Color(0xFF5C3F12),
    onTertiaryContainer = Color(0xFFF5E2C4),
    error = Color(0xFFFFB3B0),
    onError = Color(0xFF5F0010),
    errorContainer = Color(0xFF8E0022),
    onErrorContainer = Color(0xFFFFD9DA),
    background = Color(0xFF121419),         // near-black cool
    onBackground = Color(0xFFE2E4E9),
    surface = Color(0xFF15171C),
    onSurface = Color(0xFFE2E4E9),
    surfaceVariant = Color(0xFF3F444B),
    onSurfaceVariant = Color(0xFFC2C7CE),
    surfaceContainerLowest = Color(0xFF0E1014),
    surfaceContainerLow = Color(0xFF181A20),
    surfaceContainer = Color(0xFF1C1F25),
    surfaceContainerHigh = Color(0xFF262A30),
    surfaceContainerHighest = Color(0xFF31353C),
    outline = Color(0xFF8A9099),
    outlineVariant = Color(0xFF3F444B),
    inverseSurface = Color(0xFFE2E4E9),
    inverseOnSurface = Color(0xFF2A2D32),
    inversePrimary = Color(0xFF2348E0),
)
