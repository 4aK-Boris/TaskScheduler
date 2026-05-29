package cs.trade.scheduler.core.backend.cron

import com.cronutils.model.Cron
import com.cronutils.model.CronType
import com.cronutils.model.definition.CronDefinitionBuilder
import com.cronutils.model.time.ExecutionTime
import com.cronutils.parser.CronParser
import kotlin.time.Instant
import kotlin.time.toJavaInstant
import kotlin.time.toKotlinInstant
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * Thin wrapper around cron-utils. Supports two field counts, dispatched by token count:
 *
 *  - **5 fields** — classic UNIX `m h dom month dow` (e.g. `"0 9 * * MON-FRI"`). Minute
 *    granularity. The long-standing default; semantics unchanged.
 *  - **6 fields** — seconds-aware `s m h dom month dow` (e.g. `"0/10 * * * * *"` = every
 *    10 seconds). Parsed with the Spring-5.3 dialect, NOT Quartz: day-of-week keeps UNIX
 *    numbering (0/7 = SUN, names MON-SUN) and `*` is allowed in both day fields — so a
 *    6-field expression is just "UNIX + a leading seconds field", no Quartz `?` quirk.
 *
 * Timezones are IANA IDs (`"Europe/Berlin"`, `"America/New_York"`); pass `null` for UTC.
 * Cron evaluation happens in the supplied zone, so DST shifts behave the way users expect
 * (a `"0 2 * * *"` job fires once per local day even across DST boundaries).
 *
 * NOTE: sub-minute schedules only fire as promptly as the recurring poll loop ticks — see
 * `SchedulerInfraConfig.recurringPollInterval` (lower it below the cron period, else
 * `CATCH_UP_ONE` coalesces missed sub-poll slots into a single firing).
 */
public object CronExpr {

    // Pick the parser by whitespace-token count: 5 → UNIX (unchanged), 6 → seconds.
    // SPRING53 (not QUARTZ) for the 6-field case keeps UNIX day-of-week numbering and
    // allows `*` in both day fields, so 6-field is a clean superset of 5-field + seconds.
    private val unixParser = CronParser(CronDefinitionBuilder.instanceDefinitionFor(CronType.UNIX))
    private val secondsParser = CronParser(CronDefinitionBuilder.instanceDefinitionFor(CronType.SPRING53))

    /** Parse + validate. Throws `IllegalArgumentException` on bad syntax or field count. */
    public fun parse(expression: String): Cron {
        val parser = when (val fields = expression.trim().split(Regex("\\s+")).count { it.isNotEmpty() }) {
            5 -> unixParser
            6 -> secondsParser
            else -> throw IllegalArgumentException(
                "Cron must have 5 fields ('m h dom mon dow') or 6 fields ('s m h dom mon dow'); " +
                    "got $fields in '$expression'",
            )
        }
        return runCatching { parser.parse(expression).validate() }
            .getOrElse { e -> throw IllegalArgumentException("Invalid cron expression: '$expression'", e) }
    }

    /**
     * Next execution strictly after [reference] in [timezone]. Throws if the cron has
     * no future execution (only really possible for some degenerate expressions).
     */
    public fun nextExecution(
        cron: Cron,
        reference: Instant,
        timezone: ZoneId = ZoneOffset.UTC,
    ): Instant {
        val zdt = reference.toJavaInstant().atZone(timezone)
        val next = ExecutionTime.forCron(cron).nextExecution(zdt)
            .orElseThrow {
                IllegalStateException("Cron '${cron.asString()}' has no future execution after $reference")
            }
        return next.toInstant().toKotlinInstant()
    }

    /** Convenience: parse + computeNext in one go. Use when you don't need to cache the [Cron]. */
    public fun nextAfter(expression: String, reference: Instant, timezone: String? = null): Instant =
        nextExecution(parse(expression), reference, timezoneOrUtc(timezone))

    /**
     * Catch-up plan for `CATCH_UP_ALL` misfire handling (DESIGN.md 8.5). Walks cron slots
     * forward starting at [firstMissed] (inclusive — it's the slot that made the recurring
     * row due) and counts every slot that is `<= now`, capped at [limit].
     *
     * The returned [CatchUpPlan.nextTrigger] is the slot to persist as the row's next
     * trigger:
     *  - under the cap → the first slot strictly after [now] (fully caught up);
     *  - at the cap ([CatchUpPlan.capped] = true) → the next still-missed slot (`<= now`),
     *    so the remaining occurrences fire on later ticks instead of being silently dropped.
     *
     * [firstMissed] must be a real cron slot (callers pass the row's `nextTriggerAt`, which
     * always is). Because [nextExecution] is strictly-after, `slot` increases every step, so
     * the walk always terminates — at the latest when it passes [now] or hits [limit].
     */
    public fun catchUpPlan(
        expression: String,
        firstMissed: Instant,
        now: Instant,
        timezone: String? = null,
        limit: Int,
    ): CatchUpPlan {
        require(limit > 0) { "catchUpPlan limit must be positive, got $limit" }
        val cron = parse(expression)
        val zone = timezoneOrUtc(timezone)
        var slot = firstMissed
        var count = 0
        while (slot <= now && count < limit) {
            count++
            slot = nextExecution(cron, slot, zone)
        }
        return CatchUpPlan(occurrences = count, nextTrigger = slot, capped = slot <= now)
    }

    /** Result of [catchUpPlan]: how many missed slots to fire and where the next trigger lands. */
    public data class CatchUpPlan(
        val occurrences: Int,
        val nextTrigger: Instant,
        val capped: Boolean,
    )

    /** IANA → [ZoneId], with UTC fallback. */
    public fun timezoneOrUtc(iana: String?): ZoneId =
        if (iana.isNullOrBlank()) ZoneOffset.UTC else ZoneId.of(iana)
}
