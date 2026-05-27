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

    /** IANA → [ZoneId], with UTC fallback. */
    public fun timezoneOrUtc(iana: String?): ZoneId =
        if (iana.isNullOrBlank()) ZoneOffset.UTC else ZoneId.of(iana)
}
