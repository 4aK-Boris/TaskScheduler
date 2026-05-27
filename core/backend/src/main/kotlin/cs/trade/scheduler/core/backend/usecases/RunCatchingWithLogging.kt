package cs.trade.scheduler.core.backend.usecases

import kotlin.time.TimeSource

/**
 * Wraps a block with structured start/finish/elapsed logs and converts exceptions into [Result].
 *
 * Standard call convention inside a UseCase:
 * ```
 * suspend operator fun invoke(...) = runCatchingWithLogging {
 *     repository.doStuff(...) ?: error("...")
 * }
 * ```
 *
 * Don't use when the body just delegates to other UseCases — they log their own start/finish
 * and double-logging is noise.
 */
public inline fun <T> BaseUseCase.runCatchingWithLogging(
    operationName: String = this::class.simpleName ?: "UseCase",
    block: () -> T,
): Result<T> {
    val start = TimeSource.Monotonic.markNow()
    logger.debug("{} started", operationName)
    return runCatching(block).also { result ->
        val elapsed = start.elapsedNow().inWholeMilliseconds
        result.fold(
            onSuccess = { logger.debug("{} ok in {}ms", operationName, elapsed) },
            onFailure = { logger.warn("{} failed in {}ms: {}", operationName, elapsed, it.message) },
        )
    }
}
