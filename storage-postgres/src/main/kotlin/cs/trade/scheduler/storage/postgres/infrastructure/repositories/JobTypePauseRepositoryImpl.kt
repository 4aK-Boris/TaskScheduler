package cs.trade.scheduler.storage.postgres.infrastructure.repositories

import cs.trade.scheduler.storage.postgres.domain.models.JobTypePauseRow
import cs.trade.scheduler.storage.postgres.domain.repositories.JobTypePauseRepository
import cs.trade.scheduler.storage.postgres.infrastructure.tables.JobTypePauseTable
import cs.trade.scheduler.storage.postgres.infrastructure.toKotlinTime
import cs.trade.scheduler.storage.postgres.infrastructure.toOffsetDateTimeUtc
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.suspendTransaction
import org.jetbrains.exposed.v1.jdbc.update
import kotlin.time.Instant

public class JobTypePauseRepositoryImpl(
    private val database: Database,
) : JobTypePauseRepository {

    override suspend fun pause(
        payloadType: String,
        pausedBy: String,
        reason: String?,
        pausedSince: Instant,
    ): Unit = withContext(Dispatchers.IO) {
        suspendTransaction(db = database) {
            // SELECT-then-INSERT/UPDATE: portable across dialects, last-writer-wins on
            // re-pause matches the spec's behaviour (most recent operator + reason wins).
            val existing = JobTypePauseTable.selectAll()
                .where { JobTypePauseTable.payloadType eq payloadType }
                .firstOrNull()
            val sinceOdt = pausedSince.toOffsetDateTimeUtc()
            if (existing == null) {
                JobTypePauseTable.insert {
                    it[JobTypePauseTable.payloadType] = payloadType
                    it[JobTypePauseTable.pausedBy] = pausedBy
                    it[JobTypePauseTable.reason] = reason
                    it[JobTypePauseTable.pausedSince] = sinceOdt
                }
            } else {
                JobTypePauseTable.update({ JobTypePauseTable.payloadType eq payloadType }) {
                    it[JobTypePauseTable.pausedBy] = pausedBy
                    it[JobTypePauseTable.reason] = reason
                    it[JobTypePauseTable.pausedSince] = sinceOdt
                }
            }
        }
    }

    override suspend fun unpause(payloadType: String): Boolean = withContext(Dispatchers.IO) {
        suspendTransaction(db = database) {
            JobTypePauseTable.deleteWhere { JobTypePauseTable.payloadType eq payloadType } == 1
        }
    }

    override suspend fun isPaused(payloadType: String): Boolean = withContext(Dispatchers.IO) {
        suspendTransaction(db = database) {
            JobTypePauseTable.selectAll()
                .where { JobTypePauseTable.payloadType eq payloadType }
                .limit(1)
                .any()
        }
    }

    override suspend fun findPausedTypes(): Set<String> = withContext(Dispatchers.IO) {
        suspendTransaction(db = database) {
            JobTypePauseTable.selectAll()
                .map { it[JobTypePauseTable.payloadType] }
                .toSet()
        }
    }

    override suspend fun findAll(): List<JobTypePauseRow> = withContext(Dispatchers.IO) {
        suspendTransaction(db = database) {
            JobTypePauseTable.selectAll()
                .orderBy(JobTypePauseTable.pausedSince to SortOrder.DESC)
                .map { it.toRow() }
        }
    }
}

private fun ResultRow.toRow(): JobTypePauseRow = JobTypePauseRow(
    payloadType = this[JobTypePauseTable.payloadType],
    pausedSince = this[JobTypePauseTable.pausedSince].toKotlinTime(),
    pausedBy = this[JobTypePauseTable.pausedBy],
    reason = this[JobTypePauseTable.reason],
)
