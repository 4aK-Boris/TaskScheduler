package cs.trade.scheduler.dashboard.web.presentation.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Settings gear menu shared by the list screens: an auto-refresh cadence picker plus a
 * relative-vs-absolute toggle for whichever time column the screen shows. The gear tints cobalt
 * while auto-refresh is on. [timeSectionLabel] / [relativeLabel] let each screen name its own
 * time column (Jobs "Age column", Recurring "Next / Last columns").
 */
@Composable
public fun SettingsMenu(
    autoRefreshSeconds: Int?,
    onAutoRefreshChanged: (Int?) -> Unit,
    timeSectionLabel: String,
    relativeLabel: String,
    timeAbsolute: Boolean,
    onTimeModeChanged: (Boolean) -> Unit,
    absoluteLabel: String = "Absolute (14:30:05)",
) {
    var open by remember { mutableStateOf(false) }
    Box {
        Box(
            modifier = Modifier
                .size(36.dp)
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, MaterialTheme.shapes.small)
                .clickable { open = true },
            contentAlignment = Alignment.Center,
        ) {
            GearGlyph(
                if (autoRefreshSeconds != null) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            MenuSectionLabel("Auto-refresh")
            listOf<Pair<String, Int?>>(
                "Off" to null, "5 seconds" to 5, "10 seconds" to 10, "30 seconds" to 30, "1 minute" to 60,
            ).forEach { (label, secs) ->
                DropdownMenuItem(
                    text = { Text(label, style = MaterialTheme.typography.bodyMedium) },
                    onClick = { onAutoRefreshChanged(secs); open = false },
                    leadingIcon = { CheckMark(selected = autoRefreshSeconds == secs) },
                )
            }
            HorizontalDivider()
            MenuSectionLabel(timeSectionLabel)
            DropdownMenuItem(
                text = { Text(relativeLabel, style = MaterialTheme.typography.bodyMedium) },
                onClick = { onTimeModeChanged(false); open = false },
                leadingIcon = { CheckMark(selected = !timeAbsolute) },
            )
            DropdownMenuItem(
                text = { Text(absoluteLabel, style = MaterialTheme.typography.bodyMedium) },
                onClick = { onTimeModeChanged(true); open = false },
                leadingIcon = { CheckMark(selected = timeAbsolute) },
            )
        }
    }
}

@Composable
private fun MenuSectionLabel(label: String) {
    Text(
        text = label.uppercase(),
        style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 0.6.sp),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
    )
}

@Composable
private fun CheckMark(selected: Boolean) {
    val color = MaterialTheme.colorScheme.primary
    Canvas(modifier = Modifier.size(16.dp)) {
        if (!selected) return@Canvas
        val w = size.width
        val h = size.height
        val sw = w * 0.14f
        drawLine(color, Offset(w * 0.18f, h * 0.55f), Offset(w * 0.42f, h * 0.78f), sw, cap = StrokeCap.Round)
        drawLine(color, Offset(w * 0.42f, h * 0.78f), Offset(w * 0.82f, h * 0.28f), sw, cap = StrokeCap.Round)
    }
}

// "Tune" sliders glyph for the settings button (no icon dependency).
@Composable
private fun GearGlyph(color: Color) {
    Canvas(modifier = Modifier.size(16.dp)) {
        val w = size.width
        val sw = w * 0.085f
        listOf(0.28f to 0.66f, 0.5f to 0.36f, 0.72f to 0.6f).forEach { (yf, knobX) ->
            val y = size.height * yf
            drawLine(color, Offset(w * 0.15f, y), Offset(w * 0.85f, y), strokeWidth = sw, cap = StrokeCap.Round)
            drawCircle(color, radius = w * 0.09f, center = Offset(w * knobX, y))
        }
    }
}
