package cs.trade.scheduler.dashboard.web.presentation.components

/**
 * How much of a progress bar is filled, and how that fill splits between succeeded and failed.
 *
 * The two questions have different sources, which is the whole point of this type:
 *
 *  * **How far along?** — the reported `progress` fraction. For a finished job the server squares
 *    it up to 1 (see `finishTerminal`), so a SUCCEEDED job always reads as complete even when the
 *    handler's last counting sample stopped a couple of items short of `total`.
 *  * **In what proportion?** — the per-item counters, which stay exactly as the handler reported
 *    them. They are its own tally; inflating them to match the fraction would invent work.
 *
 * Deriving the length from the counters instead (`(succeeded + failed) / total`) is what left a
 * finished job's bar sitting at 99%.
 */
public data class ProgressFill(
    /** Fraction of the track that is filled, 0..1. */
    val filled: Double,

    /** Portion of the whole track painted as succeeded, 0..1. */
    val succeeded: Double,

    /** Portion of the whole track painted as failed, 0..1. */
    val failed: Double,
) {
    /** True when the handler reported per-item counts, so the fill splits into two colours. */
    public val isCounting: Boolean get() = succeeded > 0.0 || failed > 0.0
}

/**
 * Resolve a bar's geometry from what a job reports.
 *
 * [fraction] is the authority on length. It is missing for a job that only ever used the counting
 * API without a fraction ever being derived, in which case the counters supply the length instead.
 */
public fun progressFill(
    fraction: Float?,
    succeeded: Long?,
    failed: Long?,
    total: Long?,
): ProgressFill {
    val counting = succeeded != null && failed != null && total != null && total > 0L
    val processed = if (counting) succeeded!! + failed!! else 0L

    val filled = when {
        fraction != null -> fraction.toDouble().coerceIn(0.0, 1.0)
        counting -> (processed.toDouble() / total!!).coerceIn(0.0, 1.0)
        else -> 0.0
    }

    if (!counting || processed == 0L) {
        // Nothing to split: a plain fraction bar (or a counting bar before its first sample).
        return ProgressFill(filled = filled, succeeded = 0.0, failed = 0.0)
    }

    // Split the filled length in the ratio the handler actually reported. When the server has
    // completed the fraction, this stretches that ratio across the full track — 312 ok / 2 failed
    // paints as 99.4% green + 0.6% red, not as 99.4% filled with a gap at the end.
    val succeededShare = succeeded!!.toDouble() / processed
    val succeededLength = filled * succeededShare
    return ProgressFill(
        filled = filled,
        succeeded = succeededLength,
        failed = filled - succeededLength,
    )
}
