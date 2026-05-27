package cs.trade.scheduler.core.frontend.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable

/**
 * Material 3 wrapper with auto-detect dark mode. Persistent override via localStorage —
 * to be added in :dashboard-web `presentation/theme/` (DESIGN.md section 15.4).
 *
 * Custom job-state colors (green SUCCEEDED, red FAILED, ...) live in
 * `:dashboard-web` `presentation/theme/JobStateColors.kt` — they need access to
 * `MaterialTheme.colorScheme` at composition time.
 */
@Composable
public fun SchedulerTheme(
    isDark: Boolean,
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (isDark) darkColorScheme() else lightColorScheme(),
        content = content,
    )
}
