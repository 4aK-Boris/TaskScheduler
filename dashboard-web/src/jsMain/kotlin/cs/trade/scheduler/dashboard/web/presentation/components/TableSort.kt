package cs.trade.scheduler.dashboard.web.presentation.components

import react.useState

/**
 * Client-side table sort state with the dashboard's three-click cycle: click a new column to sort
 * by it in that column's natural direction, click again to flip, click a third time to fall back
 * to the table's default column.
 *
 * Every sortable screen (Workers, Types, Type Stats, Recurring, Jobs) used to inline the same
 * three-branch `when` and pair of state holders; this is that logic once.
 *
 * [naturalAscending] gives each column its sensible first direction — text columns read A→Z,
 * counts and timestamps read biggest/newest first.
 */
public class TableSort<K : Any> internal constructor(
    public val key: K,
    public val ascending: Boolean,
    private val onSortRequested: (K) -> Unit,
) {
    public fun isActive(column: K): Boolean = column == key

    public fun directionOf(column: K): SortDirection = when {
        column != key -> SortDirection.ASC
        ascending -> SortDirection.ASC
        else -> SortDirection.DESC
    }

    public fun onSort(column: K): () -> Unit = { onSortRequested(column) }

    /** Apply the current key/direction to [items] using the caller's per-column comparator. */
    public fun <T> sort(items: List<T>, comparatorFor: (K) -> Comparator<T>): List<T> {
        val sorted = items.sortedWith(comparatorFor(key))
        return if (ascending) sorted else sorted.reversed()
    }
}

public fun <K : Any> useTableSort(
    default: K,
    naturalAscending: (K) -> Boolean,
): TableSort<K> {
    var key by useState(default)
    var ascending by useState(naturalAscending(default))

    return TableSort(key, ascending) { requested ->
        when {
            requested != key -> {
                key = requested
                ascending = naturalAscending(requested)
            }
            // Second click on the active column flips it…
            ascending == naturalAscending(requested) -> ascending = !ascending
            // …and the third resets the table to its default ordering.
            else -> {
                key = default
                ascending = naturalAscending(default)
            }
        }
    }
}
