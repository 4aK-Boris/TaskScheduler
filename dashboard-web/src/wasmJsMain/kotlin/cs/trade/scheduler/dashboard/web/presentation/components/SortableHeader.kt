package cs.trade.scheduler.dashboard.web.presentation.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Sort direction for a [SortableHeaderCell]. */
public enum class SortDirection { ASC, DESC }

/**
 * Clickable table-header cell with a sort indicator — the shared building block for sortable
 * tables. Matches the plain `HeaderCell` styling (uppercase, tracked, onSurfaceVariant) but tints
 * cobalt and shows a ▲/▼ chevron when it's the active sort column. A click bubbles to [onClick];
 * the table owns the toggle logic (same column → flip direction, other column → switch).
 *
 * [numeric] right-aligns the content (the arrow then sits left of the label) to match numeric columns.
 *
 * Only the label + arrow is the hit target — the surrounding cell (its width/weight) is NOT
 * clickable, so empty column space doesn't trigger a sort. The cell box just positions the content.
 */
@Composable
public fun SortableHeaderCell(
    label: String,
    modifier: Modifier,
    active: Boolean,
    direction: SortDirection,
    onClick: () -> Unit,
    numeric: Boolean = false,
) {
    val color = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
    val text: @Composable () -> Unit = {
        Text(
            text = label.uppercase(),
            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Medium, letterSpacing = 0.6.sp),
            color = color,
        )
    }
    val arrow: @Composable () -> Unit = {
        if (active) {
            Spacer(Modifier.width(4.dp))
            SortChevron(ascending = direction == SortDirection.ASC, color = MaterialTheme.colorScheme.primary)
        }
    }
    Box(
        modifier = modifier,
        contentAlignment = if (numeric) Alignment.CenterEnd else Alignment.CenterStart,
    ) {
        Row(
            modifier = Modifier.clickable(onClick = onClick),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Numeric columns read right-aligned, so the arrow leads; text-aligned columns keep it trailing.
            if (numeric) {
                arrow()
                text()
            } else {
                text()
                arrow()
            }
        }
    }
}

@Composable
private fun SortChevron(ascending: Boolean, color: Color) {
    Canvas(modifier = Modifier.size(8.dp)) {
        val w = size.width
        val h = size.height
        val sw = w * 0.2f
        if (ascending) {
            drawLine(color, Offset(w * 0.2f, h * 0.66f), Offset(w * 0.5f, h * 0.32f), sw, cap = StrokeCap.Round)
            drawLine(color, Offset(w * 0.5f, h * 0.32f), Offset(w * 0.8f, h * 0.66f), sw, cap = StrokeCap.Round)
        } else {
            drawLine(color, Offset(w * 0.2f, h * 0.34f), Offset(w * 0.5f, h * 0.68f), sw, cap = StrokeCap.Round)
            drawLine(color, Offset(w * 0.5f, h * 0.68f), Offset(w * 0.8f, h * 0.34f), sw, cap = StrokeCap.Round)
        }
    }
}
