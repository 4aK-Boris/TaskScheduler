@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package cs.trade.scheduler.storage.postgres.infrastructure.repositories

import cs.trade.scheduler.storage.postgres.domain.models.NewOutboxEntry
import cs.trade.scheduler.storage.postgres.domain.models.OutboxEntry
import cs.trade.scheduler.storage.postgres.domain.repositories.OutboxRepository
import cs.trade.scheduler.storage.postgres.infrastructure.tables.JobTable
import cs.trade.scheduler.storage.postgres.infrastructure.tables.JobTypePauseTable
import cs.trade.scheduler.storage.postgres.infrastructure.tables.OutboxTable
import cs.trade.scheduler.storage.postgres.infrastructure.toKotlinTime
import cs.trade.scheduler.storage.postgres.infrastructure.toOffsetDateTimeUtc
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.exposed.v1.core.JoinType
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.core.isNotNull
import org.jetbrains.exposed.v1.core.less
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insertReturning
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.suspendTransaction
import org.jetbrains.exposed.v1.jdbc.update
import java.time.OffsetDateTime
import java.time.ZoneOffset
import kotlin.time.Instant

public class OutboxRepositoryImpl(
    private val database: Database,
) : OutboxRepository {

    /**
     * Outbox joined to its job's `payload_type` and the pause table. A row is *publishable*
     * when its type has no pause row. Filtering MUST happen at SQL level: the previous
     * in-code skip left paused rows unpublished at the head of the id-ordered scan, and once
     * more than a batch-size of them accumulated, `findUnpublished(limit)` returned only
     * paused rows — starving every other type silently (prod incident 2026-07-01).
     *
     * LEFT joins keep rows whose job row is gone (shouldn't happen — enqueue writes both
     * transactionally) so a dangling outbox row degrades to the old "publish it" behaviour
     * instead of hiding forever.
     */
    private val outboxWithPause = OutboxTable
        .join(JobTable, JoinType.LEFT, onColumn = OutboxTable.jobId, otherColumn = JobTable.id)
        .join(
            JobTypePauseTable,
            JoinType.LEFT,
            onColumn = JobTable.payloadType,
            otherColumn = JobTypePauseTable.payloadType,
        )

    private fun publishable(): Op<Boolean> =
        OutboxTable.publishedAt.isNull() and JobTypePauseTable.payloadType.isNull()

    override suspend fun insert(entry: NewOutboxEntry): OutboxEntry = withContext(Dispatchers.IO) {
        suspendTransaction(db = database) {
            // RETURNING * lets us pick up id + DB-side created_at default in one round-trip.
            OutboxTable.insertReturning {
                it[jobId] = entry.jobId
                it[routingKey] = entry.routingKey
                it[priority] = entry.priority
                it[delayMs] = entry.delayMs
                // createdAt has DB-side default now() — leave unset
                // publishedAt remains NULL until the publisher loop marks it
            }.single().toOutboxEntry()
        }
    }

    override suspend fun findUnpublished(limit: Int): List<OutboxEntry> = withContext(Dispatchers.IO) {
        suspendTransaction(db = database) {
            outboxWithPause
                .select(OutboxTable.columns)
                .where { publishable() }
                .orderBy(OutboxTable.id to SortOrder.ASC)
                .limit(limit)
                .map { it.toOutboxEntry() }
        }
    }

    override suspend fun markPublished(id: Long): Boolean = withContext(Dispatchers.IO) {
        suspendTransaction(db = database) {
            OutboxTable.update({ OutboxTable.id eq id }) {
                it[publishedAt] = OffsetDateTime.now(ZoneOffset.UTC)
            } == 1
        }
    }

    override suspend fun deletePublishedOlderThan(olderThan: Instant, batchSize: Int): Int =
        withContext(Dispatchers.IO) {
            suspendTransaction(db = database) {
                val thresholdOdt = olderThan.toOffsetDateTimeUtc()
                // PostgreSQL does NOT support `DELETE ... LIMIT N` — Exposed 1.x
                // surfaces that as `UnsupportedByDialectException`. The portable
                // batched-delete pattern is SELECT-pk-LIMIT-N → DELETE WHERE pk IN (…)
                // inside the same transaction. Cheap because publishedAt is indexed
                // (V1__initial_schema) and the inner SELECT is bounded by `batchSize`.
                val ids = OutboxTable
                    .select(OutboxTable.id)
                    .where {
                        OutboxTable.publishedAt.isNotNull() and (OutboxTable.publishedAt less thresholdOdt)
                    }
                    .orderBy(OutboxTable.id to SortOrder.ASC)
                    .limit(batchSize)
                    .map { it[OutboxTable.id].value }
                if (ids.isEmpty()) 0
                else OutboxTable.deleteWhere { OutboxTable.id inList ids }
            }
        }

    override suspend fun countUnpublished(): Long = withContext(Dispatchers.IO) {
        suspendTransaction(db = database) {
            outboxWithPause
                .select(OutboxTable.id)
                .where { publishable() }
                .count()
        }
    }

    override suspend fun findOldestUnpublishedCreatedAt(): Instant? = withContext(Dispatchers.IO) {
        suspendTransaction(db = database) {
            outboxWithPause
                .select(OutboxTable.createdAt)
                .where { publishable() }
                .orderBy(OutboxTable.id to SortOrder.ASC)
                .limit(1)
                .firstOrNull()
                ?.get(OutboxTable.createdAt)
                ?.toKotlinTime()
        }
    }
}

private fun ResultRow.toOutboxEntry(): OutboxEntry = OutboxEntry(
    id = this[OutboxTable.id].value,
    jobId = this[OutboxTable.jobId],
    routingKey = this[OutboxTable.routingKey],
    priority = this[OutboxTable.priority],
    delayMs = this[OutboxTable.delayMs],
    createdAt = this[OutboxTable.createdAt].toKotlinTime(),
    publishedAt = this[OutboxTable.publishedAt]?.toKotlinTime(),
)
