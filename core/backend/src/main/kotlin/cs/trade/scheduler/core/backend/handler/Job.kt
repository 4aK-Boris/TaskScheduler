package cs.trade.scheduler.core.backend.handler

/**
 * Marker interface for job payload classes. Implementations must be `@Serializable`
 * `data class`es. Example:
 *
 * ```
 * @Serializable
 * data class SendEmail(val userId: Long, val template: String) : Job
 * ```
 *
 * See DESIGN.md sections 4.3 and 12.4.
 */
public interface Job
