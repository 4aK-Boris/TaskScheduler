package cs.trade.scheduler.engine.worker.domain.models

import kotlin.time.Instant
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * In-memory snapshot of a job currently being executed by this worker. Used by the
 * heartbeat loop and graceful shutdown to know what's in flight.
 */
@OptIn(ExperimentalUuidApi::class)
public data class WorkerLease(
    val jobId: Uuid,
    val queue: String,
    val pickedUpAt: Instant,
    val lockedUntil: Instant,
)
