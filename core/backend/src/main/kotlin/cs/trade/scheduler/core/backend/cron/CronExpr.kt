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
 * Thin wrapper around cron-utils. UNIX 5-field expressions only for MVP —
 * `m h dom month dow` (e.g. `"0 9 * * MON-FRI"`).
 *
 * Timezones are IANA IDs (`"Europe/Berlin"`, `"America/New_York"`); pass `null` for UTC.
 * Cron evaluation happens in the supplied zone, so DST shifts behave the way users expect
 * (a `"0 2 * * *"` job fires once per local day even across DST boundaries).
 */
public object CronExpr {

    private val parser: CronParser = CronParser(
        CronDefinitionBuilder.instanceDefinitionFor(CronType.UNIX),
    )

    /** Parse + validate. Throws `IllegalArgumentException` on bad syntax. */
    public fun parse(expression: String): Cron =
        runCatching { parser.parse(expression).validate() }
            .getOrElse { e ->
                throw IllegalArgumentException("Invalid UNIX cron expression: '$expression'", e)
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
