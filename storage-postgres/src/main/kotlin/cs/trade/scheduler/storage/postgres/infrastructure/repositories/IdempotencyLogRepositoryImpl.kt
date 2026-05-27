@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package cs.trade.scheduler.storage.postgres.infrastructure.repositories

import cs.trade.scheduler.storage.postgres.domain.models.IdempotencyEntry
import cs.trade.scheduler.storage.postgres.domain.repositories.IdempotencyLogRepository
import cs.trade.scheduler.storage.postgres.infrastructure.tables.IdempotencyLogTable
import cs.trade.scheduler.storage.postgres.infrastructure.toKotlinTime
import cs.trade.scheduler.storage.postgres.infrastructure.toOffsetDateTimeUtc
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.less
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insertIgnore
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.suspendTransaction
import kotlin.time.Clock
import kotlin.time.Instant
import kotlin.uuid.Uuid

public class IdempotencyLogRepositoryImpl(
    private val database: Database,
) : IdempotencyLogRepository {

    override suspend fun tryMark(jobId: Uuid, action: String): Boolean =
        withContext(Dispatchers.IO) {
            suspendTransaction(db = database) {
                // INSERT ... ON CONFLICT DO NOTHING. insertedCount = 1 if the row landed,
                // 0 if the PK conflict short-circuited it — that boolean IS the dedup
                // signal, no extra SELECT needed. Race-free across concurrent workers
                // because the PK enforcement happens inside the DB.
                val stmt = IdempotencyLogTable.insertIgnore {
                    it[IdempotencyLogTable.jobId] = jobId
                    it[IdempotencyLogTable.action] = action
                    it[IdempotencyLogTable.occurredAt] = Clock.System.now().toOffsetDateTimeUtc()
                }
                stmt.insertedCount == 1
            }
        }

    override suspend fun findByJobId(jobId: Uuid, limit: Int): List<IdempotencyEntry> =
        withContext(Dispatchers.IO) {
            suspendTransaction(db = database) {
                IdempotencyLogTable.selectAll()
                    .where { IdempotencyLogTable.jobId eq jobId }
                    .orderBy(IdempotencyLogTable.occurredAt to SortOrder.ASC)
                    .limit(limit)
                    .map { it.toEntry() }
            }
        }

    override suspend fun deleteOlderThan(olderThan: Instant, batchSize: Int): Int =
        withContext(Dispatchers.IO) {
            suspendTransaction(db = database) {
                val cutoffOdt = olderThan.toOffsetDateTimeUtc()
                // PostgreSQL does not support `DELETE ... LIMIT N` and Exposed 1.x throws
                // UnsupportedByDialectException if we ask for it. The portable PG idiom is
                // `DELETE ... WHERE ctid IN (SELECT ctid FROM ... LIMIT n)`. Hand-written
                // SQL because the table has a COMPOSITE PK `(jobId, action)` — chaining a
                // 10k-long OR over individual tuples is both ugly to express in Exposed
                // and slow to plan. `ctid` is a stable physical row identifier inside a
                // single PG transaction and lets us avoid the PK altogether; the inner
                // SELECT uses the `idx_idempotency_log_occurred_at` index from
                // `V1__initial_schema.sql` so the planner picks an index range scan.
                //
                // Affected-row count comes back via `... RETURNING 1`: Exposed's
                // `exec(sql, args)` doesn't surface `executeUpdate`'s int, so we count
                // returned rows in the transform lambda. batchSize is inlined (an Int
                // constant from config, not user input — no injection vector).
                val sql = """
                    DELETE FROM idempotency_log
                    WHERE ctid IN (
                      SELECT ctid FROM idempotency_log
                      WHERE occurred_at < ?
                      LIMIT $batchSize
                    )
                    RETURNING 1
                """.trimIndent()
                exec(
                    stmt = sql,
                    args = listOf(org.jetbrains.exposed.v1.javatime.JavaOffsetDateTimeColumnType() to cutoffOdt),
                    // Force executeQuery() instead of executeUpdate(): `DELETE ... RETURNING`
                    // does return a ResultSet, but `executeUpdate` in the PG JDBC driver
                    // throws "result returned when none expected". Exposed picks the
                    // statement strategy from the SQL prefix; `SELECT` forces executeQuery.
                    explicitStatementType = org.jetbrains.exposed.v1.core.statements.StatementType.SELECT,
                ) { rs ->
                    var n = 0
                    while (rs.next()) n++
                    n
                } ?: 0
            }
        }
}

private fun ResultRow.toEntry(): IdempotencyEntry = IdempotencyEntry(
    jobId = this[IdempotencyLogTable.jobId],
    action = this[IdempotencyLogTable.action],
    occurredAt = this[IdempotencyLogTable.occurredAt].toKotlinTime(),
)
