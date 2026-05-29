package cs.trade.scheduler.shared.events

/**
 * Server-side subscription filter for the `/api/ws/events` firehose (DESIGN.md 9.2,
 * "Subscribe-with-query"). A client narrows what the server pushes by passing query
 * parameters on the WS upgrade (`?jobId=…&queue=…&type=…&eventType=…`, each repeatable);
 * the server builds this filter and only forwards matching events.
 *
 * Semantics: **conjunctive across dimensions, disjunctive within one**. An event passes
 * when, for every NON-empty dimension, it matches one of the listed values. An empty filter
 * (all dimensions empty) matches everything — the firehose's original behaviour, so a
 * subscriber that sends no params is unaffected.
 *
 * A dimension a given event type doesn't carry (e.g. [queues] for a `worker_join`) is treated
 * as a non-match: subscribing by `queue` deliberately excludes queue-less events. Likewise
 * subscribing by [jobIds] excludes worker/pause events that have no job id.
 *
 * [eventTypes] values are the wire discriminators (the `@SerialName`s: `job_state`,
 * `job_progress`, `worker_join`, …).
 */
public data class EventFilter(
    val jobIds: Set<String> = emptySet(),
    val queues: Set<String> = emptySet(),
    val payloadTypes: Set<String> = emptySet(),
    val eventTypes: Set<String> = emptySet(),
) {
    /** True when no dimension constrains anything — the server then forwards every event. */
    public val isEmpty: Boolean
        get() = jobIds.isEmpty() && queues.isEmpty() && payloadTypes.isEmpty() && eventTypes.isEmpty()

    public fun matches(event: WebSocketEvent): Boolean {
        if (eventTypes.isNotEmpty() && event.discriminator() !in eventTypes) return false
        if (jobIds.isNotEmpty() && event.jobIdOrNull() !in jobIds) return false
        if (queues.isNotEmpty() && event.queueOrNull() !in queues) return false
        if (payloadTypes.isNotEmpty() && event.payloadTypeOrNull() !in payloadTypes) return false
        return true
    }
}

/**
 * Wire discriminator (`@SerialName`) of an event. Exhaustive `when` over the sealed type —
 * adding a new [WebSocketEvent] subtype is a compile error here until it's classified, so
 * the filter can never silently miss a new event kind.
 */
internal fun WebSocketEvent.discriminator(): String = when (this) {
    is WebSocketEvent.JobCreated -> "job_created"
    is WebSocketEvent.JobStateChanged -> "job_state"
    is WebSocketEvent.JobProgress -> "job_progress"
    is WebSocketEvent.WorkerJoin -> "worker_join"
    is WebSocketEvent.WorkerLeave -> "worker_leave"
    is WebSocketEvent.RecurringTriggered -> "recurring_triggered"
    is WebSocketEvent.JobTypePaused -> "job_type_paused"
    is WebSocketEvent.JobTypeUnpaused -> "job_type_unpaused"
    is WebSocketEvent.JobDeleted -> "job_deleted"
}

private fun WebSocketEvent.jobIdOrNull(): String? = when (this) {
    is WebSocketEvent.JobCreated -> id
    is WebSocketEvent.JobStateChanged -> id
    is WebSocketEvent.JobProgress -> id
    is WebSocketEvent.JobDeleted -> id
    is WebSocketEvent.RecurringTriggered -> jobId
    else -> null
}

private fun WebSocketEvent.queueOrNull(): String? = when (this) {
    is WebSocketEvent.JobCreated -> queue
    is WebSocketEvent.JobStateChanged -> queue
    else -> null
}

private fun WebSocketEvent.payloadTypeOrNull(): String? = when (this) {
    is WebSocketEvent.JobCreated -> type
    is WebSocketEvent.JobTypePaused -> payloadType
    is WebSocketEvent.JobTypeUnpaused -> payloadType
    else -> null
}
