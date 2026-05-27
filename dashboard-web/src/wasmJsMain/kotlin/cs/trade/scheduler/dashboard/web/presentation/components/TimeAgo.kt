package cs.trade.scheduler.dashboard.web.presentation.components

import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * Human-readable "5m ago" / "2h ago" / "3d ago" / "just now". Used in list rows so the
 * column stays narrow regardless of how old the job is. Renders in UTC — the user-visible
 * delta is the point, not the wall-clock time.
 */
public fun timeAgo(instant: Instant, now: Instant = Clock.System.now()): String {
    val delta = now - instant
    return when {
        delta < 5.seconds -> "just now"
        delta < 1.minutes -> "${delta.inWholeSeconds}s ago"
        delta < 1.hours -> "${delta.inWholeMinutes}m ago"
        delta < 1.days -> "${delta.inWholeHours}h ago"
        delta < 30.days -> "${delta.inWholeDays}d ago"
        else -> "${delta.inWholeDays / 30}mo ago"
    }
}
