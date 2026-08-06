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
 * Future-facing "in 5m" / "in 2h" / "in 3d" / "due" — the upcoming-jobs counterpart to [timeAgo],
 * for a `scheduled_at` that lies ahead. Past/now collapses to "due".
 */
public fun timeUntil(instant: Instant, now: Instant = Clock.System.now()): String {
    val delta = instant - now
    if (!delta.isPositive()) return "due"
    return when {
        delta < 1.minutes -> "in ${delta.inWholeSeconds}s"
        delta < 1.hours -> "in ${delta.inWholeMinutes}m"
        delta < 1.days -> "in ${delta.inWholeHours}h"
        else -> "in ${delta.inWholeDays}d"
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
 * Full local date + time in the familiar day-first order ("30.05.2026 14:30:05") — the absolute
 * alternative for any view, where [formatClock]'s time-only loses the day for older timestamps.
 *
 * Reorders the ISO date to dd.MM.yyyy by splitting [kotlinx.datetime.LocalDate.toString]'s
 * "YYYY-MM-DD" (already zero-padded) rather than reading day/month/year properties — stays robust
 * to the kotlinx-datetime 0.8.0 property renames. [formatClock] supplies the padded HH:mm:ss, which
 * also avoids LocalDateTime.toString()'s trailing fractional seconds (…:05.123456789).
 */
public fun formatDateTime(instant: Instant): String {
    val dt = instant.toLocalDateTime(TimeZone.currentSystemDefault())
    val (year, month, day) = dt.date.toString().split("-")
    return "$day.$month.$year ${formatClock(instant)}"
}

/**
 * A duration in the largest unit that still says something: `870ms`, `5.2s`, `42s`, `3m 20s`,
 * `1h 12m`, `2d 3h`.
 *
 * Raw milliseconds stop being readable almost immediately — `22000ms` forces the reader to count
 * digits to learn it is twenty-two seconds, and `86_400_000ms` is hopeless. Below a second the
 * millisecond IS the meaningful unit, so it stays; above it we switch and keep one companion unit
 * for precision (`3m 20s`, not a bare `3m` that hides a third of a minute). The companion is
 * dropped when it would be zero, so a clean value reads `5m`, not `5m 0s`.
 *
 * Sub-10-second values keep one decimal (`5.2s`): at that scale a tenth is often the difference
 * between "fine" and "needs a look", and rounding to `5s` would throw it away.
 */
public fun formatDurationMs(millis: Long): String {
    if (millis < 0) return "—"
    if (millis < 1_000) return "${millis}ms"

    val totalSeconds = millis / 1_000
    return when {
        // One decimal, but never a pointless ".0".
        totalSeconds < 10 -> {
            val tenths = (millis + 50) / 100          // round to the nearest tenth of a second
            if (tenths % 10 == 0L) "${tenths / 10}s" else "${tenths / 10}.${tenths % 10}s"
        }

        totalSeconds < 60 -> "${totalSeconds}s"

        totalSeconds < 3_600 -> {
            val minutes = totalSeconds / 60
            val seconds = totalSeconds % 60
            if (seconds == 0L) "${minutes}m" else "${minutes}m ${seconds}s"
        }

        totalSeconds < 86_400 -> {
            val hours = totalSeconds / 3_600
            val minutes = (totalSeconds % 3_600) / 60
            if (minutes == 0L) "${hours}h" else "${hours}h ${minutes}m"
        }

        else -> {
            val days = totalSeconds / 86_400
            val hours = (totalSeconds % 86_400) / 3_600
            if (hours == 0L) "${days}d" else "${days}d ${hours}h"
        }
    }
}
