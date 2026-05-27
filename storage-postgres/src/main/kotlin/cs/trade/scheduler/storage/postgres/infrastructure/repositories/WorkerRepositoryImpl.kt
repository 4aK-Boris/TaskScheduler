package cs.trade.scheduler.storage.postgres.infrastructure.repositories

import cs.trade.scheduler.storage.postgres.domain.models.WorkerRow
import cs.trade.scheduler.storage.postgres.domain.repositories.WorkerRepository
import cs.trade.scheduler.storage.postgres.infrastructure.tables.WorkerTable
import cs.trade.scheduler.storage.postgres.infrastructure.toKotlinTime
import cs.trade.scheduler.storage.postgres.infrastructure.toOffsetDateTimeUtc
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.less
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.suspendTransaction
import org.jetbrains.exposed.v1.jdbc.update
import kotlin.time.Instant

public class WorkerRepositoryImpl(
    private val database: Database,
) : WorkerRepository {

    override suspend fun upsertHeartbeat(row: WorkerRow): Unit = withContext(Dispatchers.IO) {
        suspendTransaction(db = database) {
            // SELECT-then-INSERT/UPDATE so the upsert is portable across Exposed dialects
            // without the per-dialect `insertIgnore/onConflictDoUpdate` dance. Preserves
            // `started_at` on conflict so the dashboard shows accurate worker uptime.
            val existing = WorkerTable.selectAll()
                .where { WorkerTable.nodeId eq row.nodeId }
                .firstOrNull()
            val lastHbOdt = row.lastHeartbeat.toOffsetDateTimeUtc()
            val startedOdt = row.startedAt.toOffsetDateTimeUtc()
            val byQueueJson = encodeByQueue(row.inFlightByQueue)
            if (existing == null) {
                WorkerTable.insert {
                    it[nodeId] = row.nodeId
                    it[host] = row.host
                    it[tags] = encodeTags(row.tags)
                    it[startedAt] = startedOdt
                    it[lastHeartbeat] = lastHbOdt
                    it[inFlightCount] = row.inFlightCount
                    it[inFlightByQueue] = byQueueJson
                }
            } else {
                WorkerTable.update({ WorkerTable.nodeId eq row.nodeId }) {
                    it[host] = row.host
                    it[tags] = encodeTags(row.tags)
                    it[lastHeartbeat] = lastHbOdt
                    it[inFlightCount] = row.inFlightCount
                    it[inFlightByQueue] = byQueueJson
                    // Do NOT touch started_at — preserves dashboard uptime.
                }
            }
        }
    }

    override suspend fun findAll(limit: Int): List<WorkerRow> = withContext(Dispatchers.IO) {
        suspendTransaction(db = database) {
            WorkerTable.selectAll()
                .orderBy(WorkerTable.lastHeartbeat to SortOrder.DESC)
                .limit(limit)
                .map { it.toRow() }
        }
    }

    override suspend fun findByNodeId(nodeId: String): WorkerRow? = withContext(Dispatchers.IO) {
        suspendTransaction(db = database) {
            WorkerTable.selectAll()
                .where { WorkerTable.nodeId eq nodeId }
                .firstOrNull()
                ?.toRow()
        }
    }

    override suspend fun delete(nodeId: String): Boolean = withContext(Dispatchers.IO) {
        suspendTransaction(db = database) {
            WorkerTable.deleteWhere { WorkerTable.nodeId eq nodeId } == 1
        }
    }

    override suspend fun deleteDeadOlderThan(olderThan: Instant): Int = withContext(Dispatchers.IO) {
        suspendTransaction(db = database) {
            val cutoff = olderThan.toOffsetDateTimeUtc()
            WorkerTable.deleteWhere { WorkerTable.lastHeartbeat less cutoff }
        }
    }
}

// V1 schema declares `tags TEXT[]`. We map the column as text() in Exposed (no array
// support without an extra module) and rely on PgJDBC's `stringtype=unspecified`
// connection property to let Postgres parse the literal "{a,b,c}" form into text[]
// on INSERT/UPDATE. Reads come back as the same literal, which we split.
//
// Tags are restricted to operational metadata (e.g. "gpu", "europe-west"); commas /
// quotes / braces in tag names are unsupported in MVP.
private fun encodeTags(tags: List<String>): String =
    tags.joinToString(separator = ",", prefix = "{", postfix = "}")

private fun decodeTags(raw: String): List<String> {
    val trimmed = raw.removePrefix("{").removeSuffix("}").trim()
    if (trimmed.isEmpty()) return emptyList()
    return trimmed.split(",").map { it.trim() }
}

// JSONB serialisation for worker.in_flight_by_queue. Local Json instance (not the
// scheduler-wide one from SchedulerCoreConfig) because the storage layer must stay
// stand-alone — repos can be tested without a full Koin graph.
private val byQueueJson = Json { encodeDefaults = false }
private val byQueueSerializer = MapSerializer(String.serializer(), Int.serializer())

private fun encodeByQueue(map: Map<String, Int>): String =
    if (map.isEmpty()) "{}" else byQueueJson.encodeToString(byQueueSerializer, map)

private fun decodeByQueue(raw: String): Map<String, Int> = runCatching {
    byQueueJson.decodeFromString(byQueueSerializer, raw)
}.getOrDefault(emptyMap())

private fun ResultRow.toRow(): WorkerRow = WorkerRow(
    nodeId = this[WorkerTable.nodeId],
    host = this[WorkerTable.host],
    tags = decodeTags(this[WorkerTable.tags]),
    startedAt = this[WorkerTable.startedAt].toKotlinTime(),
    lastHeartbeat = this[WorkerTable.lastHeartbeat].toKotlinTime(),
    inFlightCount = this[WorkerTable.inFlightCount],
    inFlightByQueue = decodeByQueue(this[WorkerTable.inFlightByQueue]),
)
