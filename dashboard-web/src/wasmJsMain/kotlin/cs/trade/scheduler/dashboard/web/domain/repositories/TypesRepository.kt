package cs.trade.scheduler.dashboard.web.domain.repositories

import cs.trade.scheduler.shared.dto.TypePauseDto

public interface TypesRepository {
    public suspend fun listPaused(): List<TypePauseDto>

    /** Union of ever-enqueued payload types + currently paused. Sorted. */
    public suspend fun listKnown(): List<String>

    /** True on success (204), false if server returned an error. */
    public suspend fun pause(payloadType: String, reason: String?): Boolean

    /** True if a row was unpaused, false if 404 (no such pause). */
    public suspend fun unpause(payloadType: String): Boolean
}
