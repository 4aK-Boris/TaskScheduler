@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package cs.trade.scheduler.storage.postgres.infrastructure.scheduler

import cs.trade.scheduler.core.backend.idempotency.IdempotencyStore
import cs.trade.scheduler.storage.postgres.domain.repositories.IdempotencyLogRepository
import kotlin.uuid.Uuid

/**
 * Thin adapter exposing the storage-layer [IdempotencyLogRepository] as the higher-level
 * [IdempotencyStore] handlers depend on. Keeps the contract in `:core:backend` (handlers
 * have no compile-time dep on `:storage-postgres`) and the SQL in this module.
 */
public class PostgresIdempotencyStore(
    private val repository: IdempotencyLogRepository,
) : IdempotencyStore {

    override suspend fun tryMark(jobId: Uuid, action: String): Boolean =
        repository.tryMark(jobId, action)
}
