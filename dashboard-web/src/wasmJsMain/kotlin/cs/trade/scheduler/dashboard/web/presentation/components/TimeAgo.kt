package cs.trade.scheduler.dashboard.web.presentation.components

import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

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

/**
 * Absolute local wall-clock "HH:mm:ss" — the "exact moment it happened" alternative to
 * [timeAgo], toggled by the Age-column display setting.
 */
public fun formatClock(instant: Instant): String {
    val dt = instant.toLocalDateTime(TimeZone.currentSystemDefault())
    fun pad(n: Int): String = n.toString().padStart(2, '0')
    return "${pad(dt.hour)}:${pad(dt.minute)}:${pad(dt.second)}"
}

/**
 * Full local date + time ("2026-05-30 14:30:05") — the absolute alternative for views with the
 * room for it (JobDetail), where [formatClock]'s time-only loses the day for older timestamps.
 * Built off the ISO-8601 [kotlinx.datetime.LocalDateTime.toString] so it's robust to the date
 * property renames in kotlinx-datetime 0.8.0; the 'T' separator becomes a space for readability.
 */
public fun formatDateTime(instant: Instant): String {
    // date.toString() is a clean ISO "YYYY-MM-DD"; formatClock gives padded HH:mm:ss — composing
    // them avoids LocalDateTime.toString()'s trailing fractional seconds (…:05.123456789).
    val dt = instant.toLocalDateTime(TimeZone.currentSystemDefault())
    return "${dt.date} ${formatClock(instant)}"
}
