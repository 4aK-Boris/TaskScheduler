package cs.trade.scheduler.storage.postgres.domain

import cs.trade.scheduler.storage.postgres.domain.repositories.IdempotencyLogRepository
import cs.trade.scheduler.storage.postgres.domain.repositories.JobDependencyRepository
import cs.trade.scheduler.storage.postgres.domain.repositories.JobEventRepository
import cs.trade.scheduler.storage.postgres.domain.repositories.JobRepository
import cs.trade.scheduler.storage.postgres.domain.repositories.JobRollupRepository
import cs.trade.scheduler.storage.postgres.domain.repositories.JobTypePauseRepository
import cs.trade.scheduler.storage.postgres.domain.repositories.OutboxRepository
import cs.trade.scheduler.storage.postgres.domain.repositories.RecurringJobRepository
import cs.trade.scheduler.storage.postgres.domain.repositories.WorkerRepository

/**
 * Aggregator handle around all scheduler repositories. Single Koin-injectable entry point
 * for engine and dashboard layers — keeps DI graph small without leaking individual
 * repository interfaces if a caller only needs one.
 *
 * Concrete impl lives in `infrastructure/PostgresStorageProvider.kt`.
 */
public interface StorageProvider {
    public val jobs: JobRepository
    public val jobEvents: JobEventRepository
    public val jobDependencies: JobDependencyRepository
    public val recurringJobs: RecurringJobRepository
    public val outbox: OutboxRepository
    public val workers: WorkerRepository
    public val idempotencyLog: IdempotencyLogRepository
    public val jobTypePauses: JobTypePauseRepository
    public val jobRollups: JobRollupRepository
}
