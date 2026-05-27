package cs.trade.scheduler.dashboard.web.presentation.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * "PAUSED" amber pill — shown next to a payload type when the type sits in
 * `job_type_pause`. Colours hard-coded (not M3) for the same reason ConnectionBadge
 * uses raw colours: this is a semantic operational signal that should look like a
 * warning regardless of theme.
 */
@Composable
public fun PausedBadge(modifier: Modifier = Modifier) {
    Text(
        text = "PAUSED",
        style = MaterialTheme.typography.labelSmall,
        color = Color(0xFF7A5300),
        modifier = modifier
            .background(Color(0xFFFEF3C7), RoundedCornerShape(4.dp))
            .padding(horizontal = 6.dp, vertical = 2.dp),
    )
}
