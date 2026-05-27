@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package cs.trade.scheduler.storage.postgres.infrastructure.repositories

import cs.trade.scheduler.shared.JobState
import cs.trade.scheduler.storage.postgres.domain.models.JobEventRow
import cs.trade.scheduler.storage.postgres.domain.models.NewJobEvent
import cs.trade.scheduler.storage.postgres.domain.repositories.JobEventRepository
import cs.trade.scheduler.storage.postgres.infrastructure.tables.JobEventTable
import cs.trade.scheduler.storage.postgres.infrastructure.toKotlinTime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.insertReturning
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.suspendTransaction
import kotlin.uuid.Uuid

public class JobEventRepositoryImpl(
    private val database: Database,
) : JobEventRepository {

    override suspend fun insert(event: NewJobEvent): JobEventRow = withContext(Dispatchers.IO) {
        suspendTransaction(db = database) {
            // RETURNING * picks up id + DB-side occurred_at default.
            JobEventTable.insertReturning {
                it[jobId] = event.jobId
                it[eventType] = event.eventType
                it[prevState] = event.prevState?.name
                it[newState] = event.newState?.name
                it[actor] = event.actor
                it[errorMsg] = event.errorMsg
                it[errorStack] = event.errorStack
                // occurredAt has DB-side default now() — leave unset
            }.single().toRow()
        }
    }

    override suspend fun findByJobId(jobId: Uuid, limit: Int): List<JobEventRow> =
        withContext(Dispatchers.IO) {
            suspendTransaction(db = database) {
                JobEventTable.selectAll()
                    .where { JobEventTable.jobId eq jobId }
                    .orderBy(JobEventTable.occurredAt to SortOrder.ASC)
                    .limit(limit)
                    .map { it.toRow() }
            }
        }
}

private fun ResultRow.toRow(): JobEventRow = JobEventRow(
    id = this[JobEventTable.id].value,
    jobId = this[JobEventTable.jobId],
    eventType = this[JobEventTable.eventType],
    prevState = this[JobEventTable.prevState]?.let { JobState.valueOf(it) },
    newState = this[JobEventTable.newState]?.let { JobState.valueOf(it) },
    actor = this[JobEventTable.actor],
    errorMsg = this[JobEventTable.errorMsg],
    errorStack = this[JobEventTable.errorStack],
    occurredAt = this[JobEventTable.occurredAt].toKotlinTime(),
)
