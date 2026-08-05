package cs.trade.scheduler.dashboard.web.presentation.components

import cs.trade.scheduler.core.frontend.theme.SchedulerColors
import cs.trade.scheduler.core.frontend.theme.SchedulerRadius
import cs.trade.scheduler.core.frontend.theme.SchedulerText
import cs.trade.scheduler.core.frontend.ui.ellipsis
import cs.trade.scheduler.core.frontend.ui.flexColumn
import cs.trade.scheduler.core.frontend.ui.flexRow
import cs.trade.scheduler.shared.OnFailure
import cs.trade.scheduler.shared.dto.JobGraph
import cs.trade.scheduler.shared.dto.JobGraphEdge
import cs.trade.scheduler.shared.dto.JobView
import emotion.react.css
import react.FC
import react.Key
import react.Props
import react.dom.html.ReactHTML.div
import react.dom.html.ReactHTML.span
import react.dom.svg.ReactSVG.line
import react.dom.svg.ReactSVG.svg
import react.useMemo
import web.cssom.Auto
import web.cssom.Border
import web.cssom.Color
import web.cssom.Cursor
import web.cssom.JustifyContent
import web.cssom.LineStyle
import web.cssom.Padding
import web.cssom.Position
import web.cssom.integer
import web.cssom.pct
import web.cssom.px
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

// Node-card and spacing geometry, in CSS pixels. The layout pass works purely in these units and
// both the SVG edge layer and the absolutely-positioned cards read the same numbers, so edges
// always meet card edges exactly.
private const val NODE_W = 340.0 // wide enough for the state chip + short id on one line
private const val NODE_H = 96.0 // two lines for a long payload name + the chip/id row
private const val LEVEL_GAP = 72.0 // horizontal gap between dependency levels — room for edges
private const val SIBLING_GAP = 24.0 // vertical gap between siblings within a level

/**
 * Node-link rendering of a job's transitive dependency DAG (DESIGN.md 9.6). Lays the component
 * out left-to-right: roots in the leftmost column, each node one level to the right of its
 * deepest parent, so a dependency chain reads as a "ran → unlocked the next" flow and uses the
 * panel's width instead of growing tall. Directed parent → child edges are drawn on an SVG layer
 * behind state-coloured node cards; the [focalId] job is highlighted, and clicking any other node
 * navigates to it.
 *
 * Sizes itself to its content and scrolls horizontally — it lives inside JobDetail's outer
 * vertical scroll, so it must NOT introduce a nested vertical scroll. Only render this when the
 * graph has at least one edge; a lone node isn't worth a diagram.
 */
external interface DependencyGraphProps : Props {
    var graph: JobGraph
    var focalId: String
    var onNavigate: (String) -> Unit
}

public val DependencyGraph: FC<DependencyGraphProps> = FC { props ->
    val graph = props.graph
    val layout = useMemo(graph) { computeGraphLayout(graph) }
    val presentPolicies = graph.edges.map { it.onFailure }.distinct()

    div {
        css { flexColumn(gap = 8.px) }

        div {
            css {
                width = 100.pct
                overflowX = Auto.auto
            }
            div {
                css {
                    position = Position.relative
                    width = layout.width.px
                    height = layout.height.px
                }

                // Edge layer sits behind the cards and spans the full content box.
                svg {
                    css {
                        position = Position.absolute
                        top = 0.px
                        left = 0.px
                        width = layout.width.px
                        height = layout.height.px
                    }
                    graph.edges.forEach { edge -> renderEdge(edge, layout) }
                }

                graph.nodes.forEach { node ->
                    val pos = layout.positions[node.id]
                    if (pos != null) {
                        GraphNodeCard {
                            key = Key(node.id)
                            this.node = node
                            x = pos.first
                            y = pos.second
                            isFocal = node.id == props.focalId
                            onNavigate = props.onNavigate
                        }
                    }
                }
            }
        }

        // Legend — only the on-failure policies actually present, so a plain chain (all
        // PROPAGATE_FAILURE) gets one quiet line rather than a three-colour key.
        if (presentPolicies.isNotEmpty()) {
            div {
                css { flexRow(gap = 16.px) }
                presentPolicies.forEach { policy ->
                    div {
                        key = Key(policy.name)
                        css { flexRow(gap = 4.px) }
                        span {
                            css {
                                width = 16.px
                                height = 3.px
                                borderRadius = SchedulerRadius.extraSmall
                                backgroundColor = edgeColor(policy)
                            }
                        }
                        span {
                            css {
                                +SchedulerText.labelSmall
                                color = SchedulerColors.onSurfaceVariant
                            }
                            +"on failure: ${policy.name.lowercase().replace('_', ' ')}"
                        }
                    }
                }
            }
        }
    }
}

/**
 * One parent → child edge: a straight line from the parent's right-centre to the child's
 * left-centre, plus a two-stroke arrowhead at the child end pointing along the edge.
 */
private fun react.ChildrenBuilder.renderEdge(edge: JobGraphEdge, layout: GraphLayout) {
    val from = layout.positions[edge.parentId] ?: return
    val to = layout.positions[edge.childId] ?: return

    val startX = from.first + NODE_W
    val startY = from.second + NODE_H / 2
    val endX = to.first
    val endY = to.second + NODE_H / 2
    val stroke = edgeColorValue(edge.onFailure)

    line {
        key = Key("${edge.parentId}->${edge.childId}")
        x1 = startX
        y1 = startY
        x2 = endX
        y2 = endY
        this.stroke = stroke
        strokeWidth = EDGE_STROKE
    }

    val angle = atan2(endY - startY, endX - startX)
    listOf(-ARROW_SPREAD, ARROW_SPREAD).forEachIndexed { index, spread ->
        line {
            key = Key("${edge.parentId}->${edge.childId}-head$index")
            x1 = endX
            y1 = endY
            x2 = endX - ARROW_LEN * cos(angle + spread)
            y2 = endY - ARROW_LEN * sin(angle + spread)
            this.stroke = stroke
            strokeWidth = EDGE_STROKE
        }
    }
}

private external interface GraphNodeCardProps : Props {
    var node: JobView
    var x: Double
    var y: Double
    var isFocal: Boolean
    var onNavigate: (String) -> Unit
}

private val GraphNodeCard: FC<GraphNodeCardProps> = FC { props ->
    val node = props.node
    val focal = props.isFocal
    div {
        if (!focal) onClick = { props.onNavigate(node.id) }
        css {
            position = Position.absolute
            left = props.x.px
            top = props.y.px
            width = NODE_W.px
            height = NODE_H.px
            boxSizing = web.cssom.BoxSizing.borderBox
            padding = 12.px
            borderRadius = SchedulerRadius.medium
            backgroundColor = if (focal) SchedulerColors.surfaceVariant else SchedulerColors.surface
            border = Border(
                if (focal) 2.px else 1.px,
                LineStyle.solid,
                if (focal) SchedulerColors.primary else SchedulerColors.outlineVariant,
            )
            // Name at the top, status/id pinned to the bottom — the slack sits between them so a
            // short single-line name doesn't leave an odd gap under the status row.
            flexColumn(justify = JustifyContent.spaceBetween)
            if (!focal) {
                cursor = Cursor.pointer
                hover { borderColor = SchedulerColors.primary }
            }
        }

        span {
            // Short payload name keeps the card readable; the full FQN is on the detail screen.
            // Clamped to two lines so long type names stay legible instead of being clipped.
            title = node.payloadType
            css {
                +SchedulerText.labelLarge
                if (focal) fontWeight = integer(700)
                color = SchedulerColors.onSurface
                // Two-line clamp — the only cross-browser recipe is the legacy -webkit-box
                // trio, which has no typed constants in cssom.
                overflow = web.cssom.Overflow.hidden
                asDynamic().display = "-webkit-box"
                asDynamic()["-webkit-line-clamp"] = "2"
                asDynamic()["-webkit-box-orient"] = "vertical"
            }
            +node.payloadType.substringAfterLast('.')
        }

        div {
            css { flexRow(gap = 8.px) }
            StateChip { state = node.state }
            span {
                css {
                    +SchedulerText.mono
                    color = SchedulerColors.primary
                    ellipsis()
                }
                +node.id.take(8)
            }
        }
    }
}

private const val EDGE_STROKE = 1.5
private const val ARROW_LEN = 9.0
private const val ARROW_SPREAD = 0.45

/**
 * PROPAGATE_FAILURE is the common default → neutral outline; the rarer policies pop so an
 * operator spots a CANCEL_CHILD / IGNORE branch at a glance.
 */
private fun edgeColor(policy: OnFailure): Color = when (policy) {
    OnFailure.PROPAGATE_FAILURE -> SchedulerColors.outline
    OnFailure.CANCEL_CHILD -> SchedulerColors.warning
    OnFailure.IGNORE -> SchedulerColors.onSurfaceVariant
}

/** SVG `stroke` takes a plain string, not a typed cssom colour. */
private fun edgeColorValue(policy: OnFailure): String = when (policy) {
    OnFailure.PROPAGATE_FAILURE -> "var(--sch-outline)"
    OnFailure.CANCEL_CHILD -> "var(--sch-warning)"
    OnFailure.IGNORE -> "var(--sch-on-surface-variant)"
}

/** Positions for every node plus the overall content box size, in CSS pixels. */
private data class GraphLayout(
    val positions: Map<String, Pair<Double, Double>>,
    val width: Double,
    val height: Double,
)

/**
 * Layered DAG layout, left-to-right. Assigns each node a level via longest-path layering (Kahn
 * topological order — the DAG is acyclic by construction, DESIGN.md 22.10), orders nodes within a
 * level by the barycentre of their parents' positions to cut edge crossings, then centres each
 * column vertically. Level = column (x), order-in-level = row (y). Pure function, memoised by
 * the caller.
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

    // Group by level, preserving the node list's insertion order within each. (No sortedMapOf —
    // it's JVM-only; we sort the level keys into a plain List instead.)
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
                if (ps.isEmpty()) Double.MAX_VALUE else ps.average()
            }
        }
        row.forEachIndexed { i, id -> orderInLevel[id] = i }
    }

    // `rows` holds the levels in left-to-right order; each becomes a column. The tallest column
    // sets the content height, and shorter columns are centred against it.
    val maxCount = rows.maxOf { it.size }
    val totalHeight = maxCount * NODE_H + (maxCount - 1).coerceAtLeast(0) * SIBLING_GAP

    val positions = HashMap<String, Pair<Double, Double>>()
    rows.forEachIndexed { levelIdx, column ->
        val colHeight = column.size * NODE_H + (column.size - 1).coerceAtLeast(0) * SIBLING_GAP
        val startY = (totalHeight - colHeight) / 2
        column.forEachIndexed { i, id ->
            positions[id] = (levelIdx * (NODE_W + LEVEL_GAP)) to (startY + i * (NODE_H + SIBLING_GAP))
        }
    }
    val levelCount = rows.size
    val totalWidth = levelCount * NODE_W + (levelCount - 1).coerceAtLeast(0) * LEVEL_GAP
    return GraphLayout(positions, totalWidth, totalHeight)
}
