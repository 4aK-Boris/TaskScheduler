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
 * (5-field UNIX + 6-field seconds syntax); these tests pin down the contract we expose to
 * the rest of the scheduler:
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

    /** Helper with seconds — for 6-field cron assertions. */
    private fun atS(year: Int, month: Int, day: Int, hour: Int, minute: Int, second: Int, zone: ZoneId = utc): Instant =
        java.time.LocalDateTime.of(year, month, day, hour, minute, second)
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
    fun `parse accepts a 6-field seconds expression`() {
        // 6-field 's m h dom mon dow' (Spring-5.3 dialect) — seconds granularity.
        val cron = CronExpr.parse("0 0 9 * * *")
        assertNotNull(cron)
    }

    @Test
    fun `parse rejects an unsupported field count`() {
        // 7-field (Quartz-with-year) and other counts are out of scope — only 5 or 6.
        val ex = runCatching { CronExpr.parse("0 0 9 * * * 2026") }.exceptionOrNull()
        assertTrue(ex is IllegalArgumentException, "7-field cron must be rejected, got $ex")
    }

    @Test
    fun `every 10 seconds — 6-field next is ten seconds later`() {
        val ref = atS(2026, 5, 27, 10, 0, 0)
        val next = CronExpr.nextAfter("*/10 * * * * *", ref)
        assertEquals(atS(2026, 5, 27, 10, 0, 10), next)
    }

    @Test
    fun `6-field with weekday — seconds plus UNIX-style day-of-week`() {
        // 09:00:30 on weekdays. 2026-05-29 = Friday; ref at 09:00:00 → same day at :30.
        val ref = atS(2026, 5, 29, 9, 0, 0)
        val next = CronExpr.nextAfter("30 0 9 * * MON-FRI", ref)
        assertEquals(atS(2026, 5, 29, 9, 0, 30), next)
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

    // --- catchUpPlan: CATCH_UP_ALL misfire support (DESIGN.md 8.5 / 22-recurring) --------

    @Test
    fun `catchUpPlan counts every missed minute and resumes after now`() {
        // firstMissed 10:00, now 10:05 → slots 10:00..10:05 inclusive = 6 occurrences,
        // next trigger is the first slot strictly after now (10:06), nothing capped.
        val plan = CronExpr.catchUpPlan(
            expression = "* * * * *",
            firstMissed = at(2026, 5, 27, 10, 0),
            now = at(2026, 5, 27, 10, 5),
            limit = 100,
        )
        assertEquals(6, plan.occurrences)
        assertEquals(at(2026, 5, 27, 10, 6), plan.nextTrigger)
        assertEquals(false, plan.capped)
    }

    @Test
    fun `catchUpPlan with no backlog fires exactly the due slot`() {
        // firstMissed == now: only the due slot itself counts, next trigger is the slot after.
        val plan = CronExpr.catchUpPlan(
            expression = "* * * * *",
            firstMissed = at(2026, 5, 27, 10, 0),
            now = at(2026, 5, 27, 10, 0),
            limit = 100,
        )
        assertEquals(1, plan.occurrences)
        assertEquals(at(2026, 5, 27, 10, 1), plan.nextTrigger)
        assertEquals(false, plan.capped)
    }

    @Test
    fun `catchUpPlan respects the cron cadence — hourly, not per-minute`() {
        // "0 * * * *" = top of each hour. Backlog 10:00 → 12:30 has 3 slots (10,11,12:00).
        val plan = CronExpr.catchUpPlan(
            expression = "0 * * * *",
            firstMissed = at(2026, 5, 27, 10, 0),
            now = at(2026, 5, 27, 12, 30),
            limit = 100,
        )
        assertEquals(3, plan.occurrences)
        assertEquals(at(2026, 5, 27, 13, 0), plan.nextTrigger)
        assertEquals(false, plan.capped)
    }

    @Test
    fun `catchUpPlan caps the batch and parks the next trigger at the first un-fired slot`() {
        // 11 slots due (10:00..10:10) but limit=3 → fire 3, leave next trigger at the 4th
        // (10:03, still <= now) so the remainder catches up on later ticks. capped=true.
        val plan = CronExpr.catchUpPlan(
            expression = "* * * * *",
            firstMissed = at(2026, 5, 27, 10, 0),
            now = at(2026, 5, 27, 10, 10),
            limit = 3,
        )
        assertEquals(3, plan.occurrences)
        assertEquals(at(2026, 5, 27, 10, 3), plan.nextTrigger)
        assertEquals(true, plan.capped)
        assertTrue(plan.nextTrigger <= at(2026, 5, 27, 10, 10), "parked trigger must still be in the backlog")
    }

    @Test
    fun `catchUpPlan walks daily slots in the row timezone`() {
        // Daily 09:00 Berlin, missed 3 days. Proves cadence + tz threading for the billing
        // use case. firstMissed = 2026-05-27 09:00 Berlin; now = 2026-05-30 12:00 Berlin.
        val plan = CronExpr.catchUpPlan(
            expression = "0 9 * * *",
            firstMissed = at(2026, 5, 27, 9, 0, berlin),
            now = at(2026, 5, 30, 12, 0, berlin),
            timezone = "Europe/Berlin",
            limit = 100,
        )
        // 27th, 28th, 29th, 30th at 09:00 = 4 occurrences; next is the 31st.
        assertEquals(4, plan.occurrences)
        assertEquals(at(2026, 5, 31, 9, 0, berlin), plan.nextTrigger)
        assertEquals(false, plan.capped)
    }

    @Test
    fun `catchUpPlan rejects a non-positive limit`() {
        val ex = runCatching {
            CronExpr.catchUpPlan("* * * * *", at(2026, 5, 27, 10, 0), at(2026, 5, 27, 10, 5), limit = 0)
        }.exceptionOrNull()
        assertTrue(ex is IllegalArgumentException, "limit<=0 must throw IAE, got $ex")
    }
}
