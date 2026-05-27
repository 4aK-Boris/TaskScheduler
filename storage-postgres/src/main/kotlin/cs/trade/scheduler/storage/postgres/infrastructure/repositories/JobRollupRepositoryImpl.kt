@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package cs.trade.scheduler.storage.postgres.infrastructure.repositories

import cs.trade.scheduler.shared.JobState
import cs.trade.scheduler.storage.postgres.domain.repositories.JobRollupRepository
import cs.trade.scheduler.storage.postgres.infrastructure.tables.JobRollupTable
import cs.trade.scheduler.storage.postgres.infrastructure.tables.JobTable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.insertIgnore
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.suspendTransaction
import kotlin.uuid.Uuid

public class JobRollupRepositoryImpl(
    private val database: Database,
) : JobRollupRepository {

    override suspend fun attach(parentId: Uuid, childId: Uuid): Boolean =
        withContext(Dispatchers.IO) {
            suspendTransaction(db = database) {
                // INSERT ... ON CONFLICT DO NOTHING — duplicate attach is a no-op.
                val stmt = JobRollupTable.insertIgnore {
                    it[JobRollupTable.parentId] = parentId
                    it[JobRollupTable.childId] = childId
                }
                stmt.insertedCount == 1
            }
        }

    override suspend fun findParentsOf(childId: Uuid, limit: Int): List<Uuid> =
        withContext(Dispatchers.IO) {
            suspendTransaction(db = database) {
                JobRollupTable.selectAll()
                    .where { JobRollupTable.childId eq childId }
                    .limit(limit)
                    .map { it[JobRollupTable.parentId] }
            }
        }

    override suspend fun computeAggregateProgress(parentId: Uuid): Float? =
        withContext(Dispatchers.IO) {
            suspendTransaction(db = database) {
                // Two-query path: pull (state, progress) for each child via a JOIN, then
                // average in Kotlin. We use `JobTable.innerJoin(JobRollupTable, ...)`
                // semantics via two SELECTs because Exposed v1's AVG-with-CASE on a
                // joined column is verbose enough that splitting reads cleaner.
                val childIds = JobRollupTable.selectAll()
                    .where { JobRollupTable.parentId eq parentId }
                    .map { it[JobRollupTable.childId] }
                if (childIds.isEmpty()) return@suspendTransaction null

                // Single SELECT pulling state + progress for all children. Index on
                // JobTable.id (PK) keeps this O(N).
                val effectives = JobTable.selectAll()
                    .where { JobTable.id inList childIds }
                    .map { row ->
                        val state = JobState.valueOf(row[JobTable.state])
                        if (state.isTerminal) 1f else (row[JobTable.progress] ?: 0f)
                    }
                if (effectives.isEmpty()) return@suspendTransaction null
                (effectives.sum() / effectives.size).coerceIn(0f, 1f)
            }
        }
}
