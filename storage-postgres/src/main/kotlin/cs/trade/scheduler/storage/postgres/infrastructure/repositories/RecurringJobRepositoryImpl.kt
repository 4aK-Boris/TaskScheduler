package cs.trade.scheduler.storage.postgres.infrastructure.repositories

import cs.trade.scheduler.shared.MisfirePolicy
import cs.trade.scheduler.storage.postgres.domain.models.RecurringJobRow
import cs.trade.scheduler.storage.postgres.domain.repositories.RecurringJobRepository
import cs.trade.scheduler.storage.postgres.infrastructure.tables.RecurringJobTable
import cs.trade.scheduler.storage.postgres.infrastructure.toKotlinTime
import cs.trade.scheduler.storage.postgres.infrastructure.toOffsetDateTimeUtc
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.core.lessEq
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.suspendTransaction
import org.jetbrains.exposed.v1.jdbc.update
import kotlin.time.Instant

public class RecurringJobRepositoryImpl(
    private val database: Database,
) : RecurringJobRepository {

    override suspend fun upsert(row: RecurringJobRow): Unit = withContext(Dispatchers.IO) {
        suspendTransaction(db = database) {
            // Check whether the row exists. If yes → UPDATE only definition fields
            // (preserve enabled / last_triggered_at). If no → INSERT.
            val existing = RecurringJobTable.selectAll()
                .where { RecurringJobTable.id eq row.id }
                .firstOrNull()
            if (existing == null) {
                RecurringJobTable.insert {
                    it[id] = row.id
                    it[cron] = row.cron
                    it[timezone] = row.timezone
                    it[misfirePolicy] = row.misfirePolicy.name
                    it[queue] = row.queue
                    it[priority] = row.priority
                    it[targetNode] = row.targetNode
                    it[targetTag] = row.targetTag
                    it[payloadType] = row.payloadType
                    it[payloadJson] = row.payloadJson
                    it[lastTriggeredAt] = row.lastTriggeredAt?.toOffsetDateTimeUtc()
                    it[nextTriggerAt] = row.nextTriggerAt.toOffsetDateTimeUtc()
                    it[enabled] = row.enabled
                }
            } else {
                RecurringJobTable.update({ RecurringJobTable.id eq row.id }) {
                    it[cron] = row.cron
                    it[timezone] = row.timezone
                    it[misfirePolicy] = row.misfirePolicy.name
                    it[queue] = row.queue
                    it[priority] = row.priority
                    it[targetNode] = row.targetNode
                    it[targetTag] = row.targetTag
                    it[payloadType] = row.payloadType
                    it[payloadJson] = row.payloadJson
                    it[nextTriggerAt] = row.nextTriggerAt.toOffsetDateTimeUtc()
                    // Intentionally NOT touching: enabled, lastTriggeredAt — preserve state.
                }
            }
        }
    }

    override suspend fun findById(id: String): RecurringJobRow? = withContext(Dispatchers.IO) {
        suspendTransaction(db = database) {
            RecurringJobTable.selectAll()
                .where { RecurringJobTable.id eq id }
                .firstOrNull()
                ?.toRow()
        }
    }

    override suspend fun findDue(now: Instant, limit: Int): List<RecurringJobRow> =
        withContext(Dispatchers.IO) {
            suspendTransaction(db = database) {
                val nowOdt = now.toOffsetDateTimeUtc()
                RecurringJobTable.selectAll()
                    .where {
                        (RecurringJobTable.enabled eq true) and
                            (RecurringJobTable.nextTriggerAt lessEq nowOdt)
                    }
                    .orderBy(RecurringJobTable.nextTriggerAt to SortOrder.ASC)
                    .limit(limit)
                    .map { it.toRow() }
            }
        }

    override suspend fun markFiredAndScheduleNext(
        id: String,
        expectedLastTriggeredAt: Instant?,
        firedAt: Instant,
        next: Instant,
    ): Boolean = withContext(Dispatchers.IO) {
        suspendTransaction(db = database) {
            val expectedOdt = expectedLastTriggeredAt?.toOffsetDateTimeUtc()
            val rows = RecurringJobTable.update({
                if (expectedOdt == null) {
                    (RecurringJobTable.id eq id) and RecurringJobTable.lastTriggeredAt.isNull()
                } else {
                    (RecurringJobTable.id eq id) and (RecurringJobTable.lastTriggeredAt eq expectedOdt)
                }
            }) {
                it[lastTriggeredAt] = firedAt.toOffsetDateTimeUtc()
                it[nextTriggerAt] = next.toOffsetDateTimeUtc()
            }
            rows == 1
        }
    }

    override suspend fun disable(id: String): Boolean = withContext(Dispatchers.IO) {
        suspendTransaction(db = database) {
            RecurringJobTable.update({ RecurringJobTable.id eq id }) {
                it[enabled] = false
            } == 1
        }
    }

    override suspend fun enable(id: String): Boolean = withContext(Dispatchers.IO) {
        suspendTransaction(db = database) {
            RecurringJobTable.update({ RecurringJobTable.id eq id }) {
                it[enabled] = true
            } == 1
        }
    }

    override suspend fun findAll(limit: Int): List<RecurringJobRow> = withContext(Dispatchers.IO) {
        suspendTransaction(db = database) {
            RecurringJobTable.selectAll()
                .orderBy(RecurringJobTable.id to SortOrder.ASC)
                .limit(limit)
                .map { it.toRow() }
        }
    }
}

private fun ResultRow.toRow(): RecurringJobRow = RecurringJobRow(
    id = this[RecurringJobTable.id],
    cron = this[RecurringJobTable.cron],
    timezone = this[RecurringJobTable.timezone],
    misfirePolicy = MisfirePolicy.valueOf(this[RecurringJobTable.misfirePolicy]),
    queue = this[RecurringJobTable.queue],
    priority = this[RecurringJobTable.priority],
    targetNode = this[RecurringJobTable.targetNode],
    targetTag = this[RecurringJobTable.targetTag],
    payloadType = this[RecurringJobTable.payloadType],
    payloadJson = this[RecurringJobTable.payloadJson],
    lastTriggeredAt = this[RecurringJobTable.lastTriggeredAt]?.toKotlinTime(),
    nextTriggerAt = this[RecurringJobTable.nextTriggerAt].toKotlinTime(),
    enabled = this[RecurringJobTable.enabled],
)
