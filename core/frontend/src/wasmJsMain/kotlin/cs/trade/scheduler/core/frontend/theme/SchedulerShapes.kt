package cs.trade.scheduler.core.frontend.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

// Hard, near-square edges for the "Graphite" industrial language — chips, cards, fields and
// menus read as machined panels, not rounded consumer bubbles. Much tighter than the M3
// baseline (which rounds large/extraLarge to 16/28dp).
internal val SchedulerShapes: Shapes = Shapes(
    extraSmall = RoundedCornerShape(2.dp),
    small = RoundedCornerShape(3.dp),
    medium = RoundedCornerShape(4.dp),
    large = RoundedCornerShape(6.dp),
    extraLarge = RoundedCornerShape(10.dp),
)
