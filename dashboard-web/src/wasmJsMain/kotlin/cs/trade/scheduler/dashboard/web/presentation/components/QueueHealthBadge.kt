package cs.trade.scheduler.dashboard.web.presentation.components

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import cs.trade.scheduler.core.frontend.theme.schedulerColors
import cs.trade.scheduler.shared.dto.QueueHealthDto
import cs.trade.scheduler.shared.dto.QueueHealthStatus

/**
 * Per-queue backpressure pill rendered on the JobList header (DESIGN.md 20.10). An escalating
 * operational signal: amber = elevated, red = overloaded — drawn from the theme's semantic
 * palette so it stays consistent (and readable) in both light and dark. NORMAL queues render
 * nothing (no visual noise when everything is fine).
 */
@Composable
public fun QueueHealthBadge(item: QueueHealthDto, modifier: Modifier = Modifier) {
    if (item.status == QueueHealthStatus.NORMAL) return
    val semantic = MaterialTheme.schedulerColors
    val scheme = MaterialTheme.colorScheme
    val (bg, fg) = when (item.status) {
        QueueHealthStatus.ELEVATED -> semantic.warningContainer to semantic.onWarningContainer
        QueueHealthStatus.OVERLOADED -> scheme.errorContainer to scheme.onErrorContainer
        QueueHealthStatus.NORMAL -> error("unreachable: NORMAL short-circuited above")
    }
    Surface(color = bg, shape = RoundedCornerShape(12.dp), modifier = modifier) {
        Text(
            text = "${item.queue} (${item.depth})",
            color = fg,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
        )
    }
}
