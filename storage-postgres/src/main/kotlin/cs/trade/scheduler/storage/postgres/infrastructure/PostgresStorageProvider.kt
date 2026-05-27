package cs.trade.scheduler.storage.postgres.infrastructure

import cs.trade.scheduler.storage.postgres.domain.StorageProvider
import cs.trade.scheduler.storage.postgres.domain.repositories.IdempotencyLogRepository
import cs.trade.scheduler.storage.postgres.domain.repositories.JobDependencyRepository
import cs.trade.scheduler.storage.postgres.domain.repositories.JobEventRepository
import cs.trade.scheduler.storage.postgres.domain.repositories.JobRepository
import cs.trade.scheduler.storage.postgres.domain.repositories.JobRollupRepository
import cs.trade.scheduler.storage.postgres.domain.repositories.JobTypePauseRepository
import cs.trade.scheduler.storage.postgres.domain.repositories.OutboxRepository
import cs.trade.scheduler.storage.postgres.domain.repositories.RecurringJobRepository
import cs.trade.scheduler.storage.postgres.domain.repositories.WorkerRepository

/** Aggregator implementation of [StorageProvider] — one ctor param per per-table repo. */
public class PostgresStorageProvider(
    override val jobs: JobRepository,
    override val outbox: OutboxRepository,
    override val jobDependencies: JobDependencyRepository,
    override val recurringJobs: RecurringJobRepository,
    override val jobEvents: JobEventRepository,
    override val workers: WorkerRepository,
    override val idempotencyLog: IdempotencyLogRepository,
    override val jobRollups: JobRollupRepository,
    override val jobTypePauses: JobTypePauseRepository,
) : StorageProvider
