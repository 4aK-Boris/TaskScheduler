package cs.trade.scheduler.shared.events

import cs.trade.scheduler.shared.JobState
import kotlin.time.Instant
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Firehose WebSocket events broadcast by dashboard-server. Compact invalidation signals,
 * not full state snapshots — clients pull details via REST as needed. See DESIGN.md section 9.2.
 */
@Serializable
public sealed interface WebSocketEvent {
    public val at: Instant

    @Serializable
    @SerialName("job_created")
    public data class JobCreated(
        val id: String,
        val queue: String,
        val type: String,
        override val at: Instant,
    ) : WebSocketEvent

    @Serializable
    @SerialName("job_state")
    public data class JobStateChanged(
        val id: String,
        val from: JobState,
        val to: JobState,
        val queue: String,
        override val at: Instant,
    ) : WebSocketEvent

    @Serializable
    @SerialName("job_progress")
    public data class JobProgress(
        val id: String,
        val progress: Float,
        val msg: String?,
        override val at: Instant,
        // Counting-progress-bar metadata (JobContext.progressBar). Null for plain
        // updateProgress reports and for rollup-derived samples — defaults keep the wire
        // format backward-compatible with older clients/snapshots.
        val succeeded: Long? = null,
        val failed: Long? = null,
        val total: Long? = null,
    ) : WebSocketEvent

    @Serializable
    @SerialName("worker_join")
    public data class WorkerJoin(
        val nodeId: String,
        val host: String,
        override val at: Instant,
    ) : WebSocketEvent

    @Serializable
    @SerialName("worker_leave")
    public data class WorkerLeave(
        val nodeId: String,
        override val at: Instant,
    ) : WebSocketEvent

    @Serializable
    @SerialName("recurring_triggered")
    public data class RecurringTriggered(
        val recurringId: String,
        val jobId: String,
        override val at: Instant,
    ) : WebSocketEvent

    @Serializable
    @SerialName("job_type_paused")
    public data class JobTypePaused(
        val payloadType: String,
        val by: String,
        val reason: String? = null,
        override val at: Instant,
    ) : WebSocketEvent

    @Serializable
    @SerialName("job_type_unpaused")
    public data class JobTypeUnpaused(
        val payloadType: String,
        override val at: Instant,
    ) : WebSocketEvent

    @Serializable
    @SerialName("job_deleted")
    public data class JobDeleted(
        val id: String,
        val by: String?,
        override val at: Instant,
    ) : WebSocketEvent
}
