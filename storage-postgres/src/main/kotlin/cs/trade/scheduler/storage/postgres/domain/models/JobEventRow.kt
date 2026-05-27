@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package cs.trade.scheduler.storage.postgres.domain.models

import cs.trade.scheduler.shared.JobState
import kotlin.time.Instant
import kotlin.uuid.Uuid

// Domain mirror of one job_event row. eventType is a free-form String so user-emitted
// MANUAL_* events fit without schema changes. Standard values:
//   CREATED, PICKED_UP, SUCCEEDED, FAILED, RETRY, CANCELLED, CASCADED_FAILURE,
//   SCHEDULED, PROMOTED.
public data class JobEventRow(
    val id: Long,
    val jobId: Uuid,
    val eventType: String,
    val prevState: JobState?,
    val newState: JobState?,
    val actor: String?,
    val errorMsg: String?,
    val errorStack: String?,
    val occurredAt: Instant,
)

// Input shape for inserting a new event (no id / occurredAt — DB defaults).
public data class NewJobEvent(
    val jobId: Uuid,
    val eventType: String,
    val prevState: JobState? = null,
    val newState: JobState? = null,
    val actor: String? = null,
    val errorMsg: String? = null,
    val errorStack: String? = null,
)
