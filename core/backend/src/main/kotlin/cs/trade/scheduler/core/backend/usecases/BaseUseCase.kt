package cs.trade.scheduler.core.backend.usecases

import org.slf4j.Logger
import org.slf4j.LoggerFactory

/**
 * Base class for all UseCases.
 *
 * Convention (mirrors main project):
 * - Single public method: `operator fun invoke(...)` (suspend if needed).
 * - Returns `Result<T>` always.
 * - One logical operation per UseCase — don't pack multiple business steps.
 * - If body delegates to other UseCases — return their `Result` directly via
 *   `.mapCatching {}`/`.flatMap {}`, no [runCatchingWithLogging] wrap (nested
 *   UseCases already log).
 * - If body does its own work — wrap in [runCatchingWithLogging] for
 *   start/finish/elapsed logs.
 */
public abstract class BaseUseCase {

    /**
     * Public so that inline helpers ([runCatchingWithLogging]) can write to the same
     * logger as the subclass. Equivalent to `protected` in spirit — don't read it from
     * outside the subclass / its inline helpers.
     */
    @PublishedApi
    internal val logger: Logger = LoggerFactory.getLogger(this::class.java)
}
