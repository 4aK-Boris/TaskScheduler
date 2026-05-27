package cs.trade.scheduler.core.backend.handler

import kotlin.reflect.KClass

/**
 * Binds a `JobHandler<T>` implementation to the payload class `T`. Combined with the
 * standard Koin `@Single(binds = [JobHandler::class])`, this gives the scheduler the
 * info needed to dispatch incoming jobs to handlers.
 *
 * ```
 * @Single(binds = [JobHandler::class])
 * @JobType(SendEmail::class)
 * class SendEmailHandler(...) : JobHandler<SendEmail>
 * ```
 *
 * See DESIGN.md section 12.3.
 */
@Retention(AnnotationRetention.RUNTIME)
@Target(AnnotationTarget.CLASS)
public annotation class JobType(public val value: KClass<out Job>)
