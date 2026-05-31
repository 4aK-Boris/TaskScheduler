package cs.trade.scheduler.dashboard.web.presentation.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Text
import androidx.compose.material3.TooltipAnchorPosition
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

/**
 * Single-line text that ellipsises when it doesn't fit, reveals the full value in a tooltip on
 * hover, and shows a copy glyph on hover that copies [copyValue] to the clipboard (flashing a
 * checkmark). The copy glyph is its own click target, so this works inside a row that's itself
 * clickable (e.g. the Jobs table navigates on row-click) — clicking the glyph copies without
 * triggering the row.
 *
 * [copyValue] defaults to [text] but can carry more — e.g. a Jobs row shows a payload type's
 * simple name but copies the full FQN.
 */
@Suppress("DEPRECATION") // LocalClipboard (suspend) needs a platform ClipEntry; setText is fine on wasm.
@OptIn(ExperimentalMaterial3Api::class)
@Composable
public fun CopyableText(
    text: String,
    modifier: Modifier = Modifier,
    copyValue: String = text,
    tooltip: String = copyValue,
    style: TextStyle = LocalTextStyle.current,
    color: Color = Color.Unspecified,
) {
    val clipboard = LocalClipboardManager.current
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    var copied by remember { mutableStateOf(false) }
    LaunchedEffect(copied) {
        if (copied) {
            delay(1200)
            copied = false
        }
    }
    val iconAlpha by animateFloatAsState(if (hovered || copied) 1f else 0f, label = "copyIconAlpha")
    Row(
        modifier = modifier.hoverable(interaction),
        verticalAlignment = Alignment.CenterVertically,
        // Glyph sits right after the text with a fixed gap; its slot is always laid out (alpha
        // only toggles paint) so hovering reveals it in place without shifting the text.
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // fill = false → the text hugs its content, so the glyph stays next to short values
        // instead of floating at the column's right edge; long values still ellipsise in the cell.
        Box(modifier = Modifier.weight(1f, fill = false)) {
            TooltipBox(
                positionProvider = TooltipDefaults.rememberTooltipPositionProvider(TooltipAnchorPosition.Above),
                tooltip = { PlainTooltip { Text(tooltip) } },
                state = rememberTooltipState(isPersistent = false),
            ) {
                Text(
                    text = text,
                    style = style,
                    color = color,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        CopyGlyph(
            checked = copied,
            color = if (copied) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .alpha(iconAlpha)
                .clickable(enabled = hovered || copied) {
                    clipboard.setText(AnnotatedString(copyValue))
                    copied = true
                },
        )
    }
}

/** Two-sheet "copy" glyph, or a checkmark once copied — drawn on Canvas to avoid font/emoji issues. */
@Composable
private fun CopyGlyph(checked: Boolean, color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier.size(14.dp)) {
        val w = size.width
        val h = size.height
        if (checked) {
            val sw = w * 0.14f
            drawLine(color, Offset(w * 0.16f, h * 0.55f), Offset(w * 0.40f, h * 0.80f), sw, cap = StrokeCap.Round)
            drawLine(color, Offset(w * 0.40f, h * 0.80f), Offset(w * 0.86f, h * 0.24f), sw, cap = StrokeCap.Round)
        } else {
            val sw = w * 0.10f
            val r = CornerRadius(w * 0.12f, w * 0.12f)
            // Back sheet (top-right), then front sheet (bottom-left) overlapping it.
            drawRoundRect(color, topLeft = Offset(w * 0.34f, h * 0.06f), size = Size(w * 0.58f, h * 0.58f), cornerRadius = r, style = Stroke(sw))
            drawRoundRect(color, topLeft = Offset(w * 0.06f, h * 0.34f), size = Size(w * 0.58f, h * 0.58f), cornerRadius = r, style = Stroke(sw))
        }
    }
}
