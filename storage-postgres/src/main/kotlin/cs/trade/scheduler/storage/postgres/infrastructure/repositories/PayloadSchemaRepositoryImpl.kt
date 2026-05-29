package cs.trade.scheduler.storage.postgres.infrastructure.repositories

import cs.trade.scheduler.storage.postgres.domain.repositories.PayloadSchemaRepository
import cs.trade.scheduler.storage.postgres.infrastructure.tables.PayloadSchemaTable
import cs.trade.scheduler.storage.postgres.infrastructure.toOffsetDateTimeUtc
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.insertIgnore
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.suspendTransaction
import org.jetbrains.exposed.v1.jdbc.update
import kotlin.time.Clock

public class PayloadSchemaRepositoryImpl(private val database: Database) : PayloadSchemaRepository {

    override suspend fun recordAndDetect(
        payloadType: String,
        schemaHash: String,
    ): PayloadSchemaRepository.SchemaCheck = withContext(Dispatchers.IO) {
        suspendTransaction(db = database) {
            val now = Clock.System.now().toOffsetDateTimeUtc()
            val existing = PayloadSchemaTable.selectAll()
                .where { PayloadSchemaTable.payloadType eq payloadType }
                .firstOrNull()
                ?.get(PayloadSchemaTable.schemaHash)

            when (existing) {
                null -> {
                    // First sighting — not a drift. insertIgnore (ON CONFLICT DO NOTHING) so a
                    // concurrent worker that inserted between our SELECT and INSERT doesn't make
                    // us throw on the PK; we just treat it as "already known, no drift".
                    PayloadSchemaTable.insertIgnore {
                        it[PayloadSchemaTable.payloadType] = payloadType
                        it[PayloadSchemaTable.schemaHash] = schemaHash
                        it[PayloadSchemaTable.firstSeenAt] = now
                        it[PayloadSchemaTable.updatedAt] = now
                    }
                    PayloadSchemaRepository.SchemaCheck(changed = false, previousHash = null)
                }
                schemaHash -> PayloadSchemaRepository.SchemaCheck(changed = false, previousHash = existing)
                else -> {
                    PayloadSchemaTable.update({ PayloadSchemaTable.payloadType eq payloadType }) {
                        it[PayloadSchemaTable.schemaHash] = schemaHash
                        it[PayloadSchemaTable.updatedAt] = now
                    }
                    PayloadSchemaRepository.SchemaCheck(changed = true, previousHash = existing)
                }
            }
        }
    }
}
