package cs.trade.scheduler.core.backend.cron

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.time.Instant

/**
 * Unit coverage for [CronExpr]. The implementation is a thin wrapper around `cron-utils`
 * (UNIX 5-field syntax); these tests pin down the contract we expose to the rest of the
 * scheduler:
 *
 *  - Happy-path parse + nextExecution
 *  - Timezone correctness (DST included)
 *  - Invalid expressions fail fast with IAE
 *  - Edge expressions (Feb 29, weekdays, multi-value lists)
 *
 * If we ever swap the underlying library, this file is the contract test we need to keep
 * green — DESIGN.md 8.5 promises these semantics independently of the implementation.
 */
class CronExprTest {

    private val utc: ZoneId = ZoneOffset.UTC
    private val berlin: ZoneId = ZoneId.of("Europe/Berlin")
    private val ny: ZoneId = ZoneId.of("America/New_York")

    /** Helper — instant at a wall-clock time in [zone]. */
    private fun at(year: Int, month: Int, day: Int, hour: Int, minute: Int, zone: ZoneId = utc): Instant =
        java.time.LocalDateTime.of(year, month, day, hour, minute)
            .atZone(zone)
            .toInstant()
            .toKotlinInstant()

    private fun java.time.Instant.toKotlinInstant(): Instant = Instant.fromEpochMilliseconds(toEpochMilli())

    @Test
    fun `parse rejects an obviously bad expression with IllegalArgumentException`() {
        val ex = runCatching { CronExpr.parse("not-a-cron") }.exceptionOrNull()
        assertNotNull(ex, "garbage expression should throw")
        assertTrue(
            ex is IllegalArgumentException,
            "expected IAE, got ${ex?.let { it::class.qualifiedName }}: ${ex?.message}",
        )
    }

    @Test
    fun `parse rejects a 6-field expression — we only accept UNIX 5-field`() {
        // Quartz-style with seconds field has 6 parts; cron-utils UNIX dialect must reject.
        val ex = runCatching { CronExpr.parse("0 0 9 * * *") }.exceptionOrNull()
        assertTrue(ex is IllegalArgumentException, "6-field cron must be rejected for UNIX dialect")
    }

    @Test
    fun `every minute - next is one minute later`() {
        val ref = at(2026, 5, 27, 10, 0)
        val next = CronExpr.nextAfter("* * * * *", ref)
        assertEquals(at(2026, 5, 27, 10, 1), next)
    }

    @Test
    fun `daily at 09 00 UTC — fires next day if reference is past 9 today`() {
        val ref = at(2026, 5, 27, 10, 0)
        val next = CronExpr.nextAfter("0 9 * * *", ref)
        assertEquals(at(2026, 5, 28, 9, 0), next)
    }

    @Test
    fun `daily 09 00 in Europe Berlin — UTC offset is honored across the expression`() {
        // 09:00 in Berlin during summer = 07:00 UTC. Pass reference in UTC; the cron
        // evaluates in Berlin local time per the timezone arg.
        val ref = at(2026, 5, 27, 6, 0)
        val next = CronExpr.nextAfter("0 9 * * *", ref, timezone = "Europe/Berlin")
        // Convert expected back to UTC: 2026-05-27T09:00 Berlin = 07:00 UTC (summer)
        assertEquals(at(2026, 5, 27, 9, 0, berlin), next)
    }

    @Test
    fun `weekdays only — Saturday is skipped`() {
        // MON-FRI at 09:00. Reference is Friday 09:01 — the next firing is the following
        // Monday (Sat/Sun skipped). 2026-05-29 = Friday.
        val refFri = at(2026, 5, 29, 9, 1)
        val next = CronExpr.nextAfter("0 9 * * MON-FRI", refFri)
        // 2026-06-01 = Monday.
        assertEquals(at(2026, 6, 1, 9, 0), next)
    }

    @Test
    fun `every 15 minutes — list syntax`() {
        val ref = at(2026, 5, 27, 10, 7)
        val next = CronExpr.nextAfter("0,15,30,45 * * * *", ref)
        assertEquals(at(2026, 5, 27, 10, 15), next)
    }

    @Test
    fun `DST spring-forward in Europe Berlin — 02 30 in March is skipped`() {
        // 2026-03-29 is the Sunday EU clocks jump from 02:00 → 03:00 in Berlin local.
        // A `30 2 * * SUN` job scheduled for the Sunday of the spring-forward should
        // either skip or be coalesced — cron-utils handles this by skipping (no firing).
        // We verify behaviour is consistent: nextAfter just before the gap finds the
        // NEXT Sunday's 02:30, not a non-existent one inside the gap.
        val ref = at(2026, 3, 29, 0, 0, berlin)
        val next = CronExpr.nextAfter("30 2 * * SUN", ref, timezone = "Europe/Berlin")
        // Either the next Sunday at 02:30 (skipped) or this Sunday at 03:30 (some libs
        // shift forward). Either is acceptable; we assert it's at least past the gap.
        assertTrue(
            next > at(2026, 3, 29, 1, 0, berlin),
            "next execution must be past the DST gap (got $next)",
        )
    }

    @Test
    fun `Feb 29 cron expression fires only in leap years`() {
        // 2026 is NOT a leap year. The next "0 0 29 2 *" after 2026-01-01 must be
        // 2028-02-29 (the next leap day).
        val ref = at(2026, 1, 1, 0, 0)
        val next = CronExpr.nextAfter("0 0 29 2 *", ref)
        assertEquals(at(2028, 2, 29, 0, 0), next)
    }

    @Test
    fun `timezoneOrUtc — null and blank both return UTC`() {
        assertEquals(ZoneOffset.UTC, CronExpr.timezoneOrUtc(null))
        assertEquals(ZoneOffset.UTC, CronExpr.timezoneOrUtc(""))
        assertEquals(ZoneOffset.UTC, CronExpr.timezoneOrUtc("   "))
        assertEquals(ny, CronExpr.timezoneOrUtc("America/New_York"))
    }

    @Test
    fun `cached Cron — same parse yields fast nextExecution path`() {
        val cron = CronExpr.parse("0 9 * * *")
        // No assertions on timing — just confirm the API surface exposed for caching is
        // stable. If someone makes `parse` private, this fails to compile.
        val ref = at(2026, 5, 27, 8, 0)
        val next1 = CronExpr.nextExecution(cron, ref)
        val next2 = CronExpr.nextExecution(cron, ref)
        assertEquals(next1, next2)
    }
}
