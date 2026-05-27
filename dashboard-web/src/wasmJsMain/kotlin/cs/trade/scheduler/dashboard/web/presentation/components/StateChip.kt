package cs.trade.scheduler.dashboard.web.presentation.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import cs.trade.scheduler.dashboard.web.presentation.theme.JobStateColors
import cs.trade.scheduler.shared.JobState

/** Pill-shaped colour-coded job-state badge, used in list rows and the detail header. */
@Composable
public fun StateChip(state: JobState, modifier: Modifier = Modifier) {
    Text(
        text = state.name,
        style = MaterialTheme.typography.labelSmall,
        color = JobStateColors.fg(state),
        modifier = modifier
            .background(color = JobStateColors.bg(state), shape = RoundedCornerShape(8.dp))
            .padding(horizontal = 10.dp, vertical = 4.dp),
    )
}
