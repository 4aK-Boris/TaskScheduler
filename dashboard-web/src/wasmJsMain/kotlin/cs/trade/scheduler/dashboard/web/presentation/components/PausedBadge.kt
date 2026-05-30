package cs.trade.scheduler.dashboard.web.presentation.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import cs.trade.scheduler.core.frontend.theme.schedulerColors

/**
 * "PAUSED" warning pill — shown next to a payload type when the type sits in `job_type_pause`.
 * Uses the theme's semantic warning palette so it reads as a warning in both light and dark
 * (was a hardcoded light-only pastel before).
 */
@Composable
public fun PausedBadge(modifier: Modifier = Modifier) {
    val semantic = MaterialTheme.schedulerColors
    Text(
        text = "PAUSED",
        style = MaterialTheme.typography.labelSmall,
        color = semantic.onWarningContainer,
        modifier = modifier
            .background(semantic.warningContainer, RoundedCornerShape(4.dp))
            .padding(horizontal = 6.dp, vertical = 2.dp),
    )
}
