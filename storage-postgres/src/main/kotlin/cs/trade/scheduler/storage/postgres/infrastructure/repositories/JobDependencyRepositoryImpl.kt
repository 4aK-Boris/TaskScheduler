@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package cs.trade.scheduler.storage.postgres.infrastructure.repositories

import cs.trade.scheduler.shared.OnFailure
import cs.trade.scheduler.storage.postgres.domain.models.JobDependency
import cs.trade.scheduler.storage.postgres.domain.repositories.JobDependencyRepository
import cs.trade.scheduler.storage.postgres.infrastructure.tables.JobDependencyTable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.suspendTransaction
import kotlin.uuid.Uuid

public class JobDependencyRepositoryImpl(
    private val database: Database,
) : JobDependencyRepository {

    override suspend fun insert(parentId: Uuid, childId: Uuid, onFailure: OnFailure): Unit =
        withContext(Dispatchers.IO) {
            suspendTransaction(db = database) {
                JobDependencyTable.insert {
                    it[this.parentId] = parentId
                    it[this.childId] = childId
                    it[this.onFailure] = onFailure.name
                }
                Unit
            }
        }

    override suspend fun findChildrenOfParent(parentId: Uuid): List<JobDependency> =
        withContext(Dispatchers.IO) {
            suspendTransaction(db = database) {
                JobDependencyTable.selectAll()
                    .where { JobDependencyTable.parentId eq parentId }
                    .map { it.toJobDependency() }
            }
        }

    override suspend fun findParentsOfChild(childId: Uuid): List<JobDependency> =
        withContext(Dispatchers.IO) {
            suspendTransaction(db = database) {
                JobDependencyTable.selectAll()
                    .where { JobDependencyTable.childId eq childId }
                    .map { it.toJobDependency() }
            }
        }
}

private fun ResultRow.toJobDependency(): JobDependency = JobDependency(
    parentId = this[JobDependencyTable.parentId],
    childId = this[JobDependencyTable.childId],
    onFailure = OnFailure.valueOf(this[JobDependencyTable.onFailure]),
)
