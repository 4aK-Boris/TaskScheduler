package cs.trade.scheduler.core.frontend.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * Semantic colours Material 3 has no role for — success / warning / info. Job-state chips, the
 * connection badge and queue-health badges need a green/amber/blue that ADAPTS to light vs dark
 * instead of hardcoding light-only hex (which washes out on a dark surface).
 *
 * Provided by [SchedulerTheme] via [LocalSchedulerSemanticColors]; read through
 * `MaterialTheme.schedulerColors` — same ergonomics as `MaterialTheme.colorScheme`.
 */
public data class SchedulerSemanticColors(
    val success: Color,
    val onSuccess: Color,
    val successContainer: Color,
    val onSuccessContainer: Color,
    val warning: Color,
    val onWarning: Color,
    val warningContainer: Color,
    val onWarningContainer: Color,
    val info: Color,
    val onInfo: Color,
    val infoContainer: Color,
    val onInfoContainer: Color,
)

internal val SchedulerLightSemanticColors = SchedulerSemanticColors(
    success = Color(0xFF0E9F6E), onSuccess = Color(0xFFFFFFFF),
    successContainer = Color(0xFFCFF5E7), onSuccessContainer = Color(0xFF00382A),
    warning = Color(0xFFB45309), onWarning = Color(0xFFFFFFFF),
    warningContainer = Color(0xFFFFE0B8), onWarningContainer = Color(0xFF2E1500),
    info = Color(0xFF2348E0), onInfo = Color(0xFFFFFFFF),
    infoContainer = Color(0xFFDBE2FF), onInfoContainer = Color(0xFF001550),
)

internal val SchedulerDarkSemanticColors = SchedulerSemanticColors(
    success = Color(0xFF7FD8B4), onSuccess = Color(0xFF00382A),
    successContainer = Color(0xFF00513A), onSuccessContainer = Color(0xFFCFF5E7),
    warning = Color(0xFFFFB870), onWarning = Color(0xFF492900),
    warningContainer = Color(0xFF683D00), onWarningContainer = Color(0xFFFFE0B8),
    info = Color(0xFFAEC0FF), onInfo = Color(0xFF002C86),
    infoContainer = Color(0xFF1A3BC0), onInfoContainer = Color(0xFFDBE2FF),
)

/** Theme-scoped semantic palette. Defaults to light so previews without [SchedulerTheme] still render. */
public val LocalSchedulerSemanticColors: ProvidableCompositionLocal<SchedulerSemanticColors> =
    staticCompositionLocalOf { SchedulerLightSemanticColors }

/** Accessor mirroring `MaterialTheme.colorScheme`, e.g. `MaterialTheme.schedulerColors.successContainer`. */
public val MaterialTheme.schedulerColors: SchedulerSemanticColors
    @Composable @ReadOnlyComposable get() = LocalSchedulerSemanticColors.current
