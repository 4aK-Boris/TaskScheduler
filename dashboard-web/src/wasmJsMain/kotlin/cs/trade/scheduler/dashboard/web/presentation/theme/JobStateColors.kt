package cs.trade.scheduler.dashboard.web.presentation.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.Color
import cs.trade.scheduler.shared.JobState

/**
 * Single source of truth for state → colour mapping in the dashboard. Cards / chips /
 * list rows all read from here so the visual language stays consistent.
 *
 * Colours derived from MaterialTheme so a future dark-mode toggle (DESIGN.md 15.4)
 * just swaps `MaterialTheme.colorScheme` and nothing here needs to change.
 */
public object JobStateColors {

    /** Background colour for a state chip / row accent. */
    @Composable
    @ReadOnlyComposable
    public fun bg(state: JobState): Color = when (state) {
        JobState.AWAITING_DEPS -> MaterialTheme.colorScheme.surfaceVariant
        JobState.SCHEDULED -> MaterialTheme.colorScheme.tertiaryContainer
        JobState.ENQUEUED -> MaterialTheme.colorScheme.primaryContainer
        JobState.PROCESSING -> MaterialTheme.colorScheme.secondaryContainer
        JobState.AWAITING_RETRY -> MaterialTheme.colorScheme.errorContainer
        JobState.SUCCEEDED -> Color(0xFFC8E6C9)
        JobState.FAILED -> MaterialTheme.colorScheme.errorContainer
        JobState.CANCELLED -> MaterialTheme.colorScheme.surfaceVariant
    }

    /** Text colour readable against [bg]. */
    @Composable
    @ReadOnlyComposable
    public fun fg(state: JobState): Color = when (state) {
        JobState.AWAITING_DEPS -> MaterialTheme.colorScheme.onSurfaceVariant
        JobState.SCHEDULED -> MaterialTheme.colorScheme.onTertiaryContainer
        JobState.ENQUEUED -> MaterialTheme.colorScheme.onPrimaryContainer
        JobState.PROCESSING -> MaterialTheme.colorScheme.onSecondaryContainer
        JobState.AWAITING_RETRY -> MaterialTheme.colorScheme.onErrorContainer
        JobState.SUCCEEDED -> Color(0xFF1B5E20)
        JobState.FAILED -> MaterialTheme.colorScheme.onErrorContainer
        JobState.CANCELLED -> MaterialTheme.colorScheme.onSurfaceVariant
    }
}
