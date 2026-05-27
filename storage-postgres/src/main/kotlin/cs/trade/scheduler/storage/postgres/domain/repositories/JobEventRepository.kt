@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package cs.trade.scheduler.storage.postgres.domain.repositories

import cs.trade.scheduler.storage.postgres.domain.models.JobEventRow
import cs.trade.scheduler.storage.postgres.domain.models.NewJobEvent
import kotlin.uuid.Uuid

// Append-only audit log for job state transitions. See DESIGN.md sections 6 and 7.1.
//
// Writes are always done inside the same DB transaction as the corresponding job UPDATE
// — atomicity matters: a transition that committed without its event would corrupt the
// dashboard timeline, and vice-versa.
public interface JobEventRepository {

    public suspend fun insert(event: NewJobEvent): JobEventRow

    // Ordered oldest-first by occurred_at. Capped by [limit] so a runaway retry loop
    // doesn't blow up the JobDetail response.
    public suspend fun findByJobId(jobId: Uuid, limit: Int = 200): List<JobEventRow>
}
