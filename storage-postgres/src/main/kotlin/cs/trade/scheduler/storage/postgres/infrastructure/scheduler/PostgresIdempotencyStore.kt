@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package cs.trade.scheduler.storage.postgres.infrastructure.scheduler

import cs.trade.scheduler.core.backend.idempotency.IdempotencyMetrics
import cs.trade.scheduler.core.backend.idempotency.IdempotencyStore
import cs.trade.scheduler.storage.postgres.domain.repositories.IdempotencyLogRepository
import kotlin.uuid.Uuid

/**
 * Thin adapter exposing the storage-layer [IdempotencyLogRepository] as the higher-level
 * [IdempotencyStore] handlers depend on. Keeps the contract in `:core:backend` (handlers
 * have no compile-time dep on `:storage-postgres`) and the SQL in this module.
 *
 * Feeds [IdempotencyMetrics] on dedup hits so the dashboard can surface
 * `scheduler_idempotency_dedup_total{action=…}` per DESIGN.md 22.5. Default binding is
 * the no-op sink — apps without a MeterRegistry pay zero overhead.
 */
public class PostgresIdempotencyStore(
    private val repository: IdempotencyLogRepository,
    private val metrics: IdempotencyMetrics = IdempotencyMetrics.Noop,
) : IdempotencyStore {

    override suspend fun tryMark(jobId: Uuid, action: String): Boolean {
        val marked = repository.tryMark(jobId, action)
        if (!marked) metrics.recordDedup(action)
        return marked
    }
}
