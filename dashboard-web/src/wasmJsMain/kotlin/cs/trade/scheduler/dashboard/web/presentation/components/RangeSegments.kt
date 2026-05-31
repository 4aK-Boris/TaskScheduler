package cs.trade.scheduler.dashboard.web.presentation.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import cs.trade.scheduler.shared.dto.TypeStatsRange

/**
 * Compact segmented time-window control (1h … 30d) — the selected segment fills cobalt. Shared by
 * the Type Stats and Stats screens so the picker reads and behaves identically on both.
 */
@Composable
public fun RangeSegments(
    current: TypeStatsRange,
    onSelected: (TypeStatsRange) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .clip(MaterialTheme.shapes.small)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, MaterialTheme.shapes.small),
    ) {
        TypeStatsRange.entries.forEach { range ->
            val selected = range == current
            val bg by animateColorAsState(
                if (selected) MaterialTheme.colorScheme.primary else Color.Transparent,
            )
            val fg by animateColorAsState(
                if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Box(
                modifier = Modifier
                    .background(bg)
                    .clickable { onSelected(range) }
                    .padding(horizontal = 14.dp, vertical = 8.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(text = range.shortLabel(), style = MaterialTheme.typography.labelMedium, color = fg)
            }
        }
    }
}

private fun TypeStatsRange.shortLabel(): String = when (this) {
    TypeStatsRange.LAST_1_HOUR -> "1h"
    TypeStatsRange.LAST_3_HOURS -> "3h"
    TypeStatsRange.LAST_6_HOURS -> "6h"
    TypeStatsRange.LAST_12_HOURS -> "12h"
    TypeStatsRange.LAST_24_HOURS -> "24h"
    TypeStatsRange.LAST_3_DAYS -> "3d"
    TypeStatsRange.LAST_7_DAYS -> "7d"
    TypeStatsRange.LAST_30_DAYS -> "30d"
}
