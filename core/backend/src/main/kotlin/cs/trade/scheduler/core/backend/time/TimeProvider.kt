package cs.trade.scheduler.core.backend.time

import kotlin.time.Clock
import kotlin.time.Instant
import org.koin.core.annotation.Single

/**
 * Time abstraction for testability. All scheduler code must read "now" through this
 * interface — never `Instant.now()` directly. See main project's `cs.trade.core.time`.
 */
public interface TimeProvider {
    public fun now(): Instant
    public fun nowMillis(): Long
}

@Single
public class DefaultTimeProvider : TimeProvider {
    override fun now(): Instant = Clock.System.now()
    override fun nowMillis(): Long = Clock.System.now().toEpochMilliseconds()
}
