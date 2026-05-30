package cs.trade.scheduler.dashboard.web.presentation.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cs.trade.scheduler.shared.OnFailure
import cs.trade.scheduler.shared.dto.JobGraph
import cs.trade.scheduler.shared.dto.JobGraphEdge
import cs.trade.scheduler.shared.dto.JobView
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

// Node-card and spacing geometry, all in dp. The layout pass works purely in these units;
// the Canvas converts to px at draw time via `.dp.toPx()` so edges line up with the
// dp-positioned cards regardless of display density.
private const val NODE_W = 340f // wide enough for the state chip + short id on one line
private const val NODE_H = 96f // two lines for a long payload name + the chip/id row, with breathing room
private const val LEVEL_GAP = 72f // horizontal gap between dependency levels (columns) — room for edges
private const val SIBLING_GAP = 24f // vertical gap between siblings within a level

/**
 * Node-link rendering of a job's transitive dependency DAG (DESIGN.md 9.6). Lays the component
 * out left-to-right: roots in the leftmost column, each node one level to the right of its
 * deepest parent, so a dependency chain reads as a "ran → unlocked the next" flow and uses the
 * panel's width instead of growing tall. Draws directed parent → child edges on a [Canvas]
 * behind state-coloured node cards, highlights the [focalId] job, navigates on click.
 *
 * Sizes itself to its content and scrolls horizontally — it lives inside JobDetail's outer
 * vertical scroll, so it must NOT introduce a nested vertical scroll. Only call this when
 * the graph has at least one edge; a lone node isn't worth a diagram.
 */
@Composable
public fun DependencyGraph(
    graph: JobGraph,
    focalId: String,
    onNavigate: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val layout = remember(graph) { computeGraphLayout(graph) }

    // Resolve edge colours here (DrawScope can't read MaterialTheme) and close over the map
    // inside the Canvas draw lambda. PROPAGATE_FAILURE is the common default → neutral; the
    // rarer policies pop so an operator spots a CANCEL_CHILD / IGNORE branch at a glance.
    val propagateColor = MaterialTheme.colorScheme.outline
    val edgeColors: Map<OnFailure, Color> = mapOf(
        OnFailure.PROPAGATE_FAILURE to propagateColor,
        OnFailure.CANCEL_CHILD to Color(0xFFEF6C00),
        OnFailure.IGNORE to Color(0xFF78909C),
    )

    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(
            Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
        ) {
            Box(Modifier.size(layout.widthDp.dp, layout.heightDp.dp)) {
                Canvas(Modifier.fillMaxSize()) {
                    graph.edges.forEach { edge ->
                        val from = layout.positions[edge.parentId] ?: return@forEach
                        val to = layout.positions[edge.childId] ?: return@forEach
                        // parent right-centre -> child left-centre (left-to-right layout)
                        val start = Offset((from.first + NODE_W).dp.toPx(), (from.second + NODE_H / 2f).dp.toPx())
                        val end = Offset(to.first.dp.toPx(), (to.second + NODE_H / 2f).dp.toPx())
                        val color = edgeColors[edge.onFailure] ?: propagateColor
                        val stroke = 1.5.dp.toPx()
                        drawLine(color, start, end, strokeWidth = stroke)
                        // Arrowhead at the child end, pointing along the edge direction.
                        val angle = atan2(end.y - start.y, end.x - start.x)
                        val headLen = 9.dp.toPx()
                        val spread = 0.45f
                        drawLine(
                            color,
                            end,
                            Offset(end.x - headLen * cos(angle - spread), end.y - headLen * sin(angle - spread)),
                            strokeWidth = stroke,
                        )
                        drawLine(
                            color,
                            end,
                            Offset(end.x - headLen * cos(angle + spread), end.y - headLen * sin(angle + spread)),
                            strokeWidth = stroke,
                        )
                    }
                }

                graph.nodes.forEach { node ->
                    val pos = layout.positions[node.id] ?: return@forEach
                    Box(Modifier.offset(pos.first.dp, pos.second.dp).size(NODE_W.dp, NODE_H.dp)) {
                        GraphNodeCard(
                            node = node,
                            isFocal = node.id == focalId,
                            onClick = { if (node.id != focalId) onNavigate(node.id) },
                        )
                    }
                }
            }
        }

        // Legend — only the on-failure policies actually present, so a plain chain (all
        // PROPAGATE_FAILURE) gets one quiet line rather than a three-colour key.
        val present = graph.edges.map { it.onFailure }.distinct()
        if (present.isNotEmpty()) {
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                present.forEach { policy ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Box(
                            Modifier
                                .size(16.dp, 3.dp)
                                .clip(RoundedCornerShape(2.dp))
                                .background(edgeColors[policy] ?: propagateColor),
                        )
                        Text(
                            text = "on failure: ${policy.name.lowercase().replace('_', ' ')}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun GraphNodeCard(node: JobView, isFocal: Boolean, onClick: () -> Unit) {
    val borderColor = if (isFocal) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = if (isFocal) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.surface,
        border = BorderStroke(if (isFocal) 2.dp else 1.dp, borderColor),
        modifier = Modifier.fillMaxSize().clickable(enabled = !isFocal, onClick = onClick),
    ) {
        Column(
            Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 12.dp),
            // Name stays at the top, status/id pinned to the bottom — the slack sits between them
            // so short single-line names don't leave an odd gap under the status row.
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                // Short payload name keeps the card readable; full FQN is on the detail screen.
                // Wraps to two lines so long type names stay legible instead of being clipped.
                text = node.payloadType.substringAfterLast('.'),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = if (isFocal) FontWeight.Bold else FontWeight.Normal,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                StateChip(node.state)
                Text(
                    text = node.id.take(8),
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** Pixel-free (dp) positions for every node plus the overall content box size. */
private data class GraphLayout(
    val positions: Map<String, Pair<Float, Float>>,
    val widthDp: Float,
    val heightDp: Float,
)

/**
 * Layered DAG layout, left-to-right. Assigns each node a level via longest-path layering (Kahn
 * topological order — the DAG is acyclic by construction, DESIGN.md 22.10), orders nodes within a
 * level by the barycentre of their parents' positions to cut edge crossings, then centres each
 * column vertically and converts to dp coordinates. Level = column (x), order-in-level = row (y).
 * Pure function, memoised by the caller.
 */
private fun computeGraphLayout(graph: JobGraph): GraphLayout {
    val ids = graph.nodes.map { it.id }
    if (ids.isEmpty()) return GraphLayout(emptyMap(), NODE_W, NODE_H)
    val idSet = ids.toHashSet()

    val childrenOf = HashMap<String, MutableList<String>>().apply { ids.forEach { put(it, mutableListOf()) } }
    val parentsOf = HashMap<String, MutableList<String>>().apply { ids.forEach { put(it, mutableListOf()) } }
    graph.edges.forEach { e: JobGraphEdge ->
        if (e.parentId in idSet && e.childId in idSet) {
            childrenOf.getValue(e.parentId).add(e.childId)
            parentsOf.getValue(e.childId).add(e.parentId)
        }
    }

    // Longest-path level assignment via Kahn's algorithm.
    val indegree = HashMap<String, Int>().apply { ids.forEach { put(it, parentsOf.getValue(it).size) } }
    val level = HashMap<String, Int>().apply { ids.forEach { put(it, 0) } }
    val queue = ArrayDeque(ids.filter { indegree.getValue(it) == 0 })
    val seen = HashSet<String>()
    while (queue.isNotEmpty()) {
        val n = queue.removeFirst()
        if (!seen.add(n)) continue
        val nl = level.getValue(n)
        childrenOf.getValue(n).forEach { c ->
            if (nl + 1 > level.getValue(c)) level[c] = nl + 1
            indegree[c] = indegree.getValue(c) - 1
            if (indegree.getValue(c) == 0) queue.add(c)
        }
    }

    // Group by level, preserving the node list's insertion order within each. (No
    // sortedMapOf — it's JVM-only; we sort the level keys into a plain List instead.)
    val byLevel = HashMap<Int, MutableList<String>>()
    ids.forEach { byLevel.getOrPut(level.getValue(it)) { mutableListOf() }.add(it) }
    val rows: List<MutableList<String>> = byLevel.keys.sorted().map { byLevel.getValue(it) }

    // Order within levels top-down by barycentre of parent positions (one pass — enough to
    // straighten chains and most trees without full Sugiyama crossing minimisation).
    val orderInLevel = HashMap<String, Int>()
    rows.forEachIndexed { rowIdx, row ->
        if (rowIdx > 0) {
            row.sortBy { id ->
                val ps = parentsOf.getValue(id).mapNotNull { orderInLevel[it] }
                if (ps.isEmpty()) Float.MAX_VALUE else ps.average().toFloat()
            }
        }
        row.forEachIndexed { i, id -> orderInLevel[id] = i }
    }

    // `rows` holds the levels in left-to-right order; each becomes a column. The tallest column
    // sets the content height, and shorter columns are centred against it.
    val maxCount = rows.maxOf { it.size }
    val totalHeight = maxCount * NODE_H + (maxCount - 1).coerceAtLeast(0) * SIBLING_GAP

    val positions = HashMap<String, Pair<Float, Float>>()
    rows.forEachIndexed { levelIdx, column ->
        val colHeight = column.size * NODE_H + (column.size - 1).coerceAtLeast(0) * SIBLING_GAP
        val startY = (totalHeight - colHeight) / 2f
        column.forEachIndexed { i, id ->
            positions[id] = (levelIdx * (NODE_W + LEVEL_GAP)) to (startY + i * (NODE_H + SIBLING_GAP))
        }
    }
    val levelCount = rows.size
    val totalWidth = levelCount * NODE_W + (levelCount - 1).coerceAtLeast(0) * LEVEL_GAP
    return GraphLayout(positions, totalWidth, totalHeight)
}
