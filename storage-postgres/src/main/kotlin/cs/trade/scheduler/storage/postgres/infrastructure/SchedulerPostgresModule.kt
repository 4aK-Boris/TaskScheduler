package cs.trade.scheduler.storage.postgres.infrastructure

import cs.trade.scheduler.core.backend.Scheduler
import cs.trade.scheduler.core.backend.SchedulerCoreConfig
import cs.trade.scheduler.core.backend.archival.ArchivalSink
import cs.trade.scheduler.core.backend.context.ContextCapture
import cs.trade.scheduler.core.backend.events.EventBus
import cs.trade.scheduler.core.backend.idempotency.IdempotencyStore
import cs.trade.scheduler.storage.postgres.domain.StorageProvider
import cs.trade.scheduler.storage.postgres.domain.repositories.IdempotencyLogRepository
import cs.trade.scheduler.storage.postgres.domain.repositories.JobDependencyRepository
import cs.trade.scheduler.storage.postgres.domain.repositories.JobEventRepository
import cs.trade.scheduler.storage.postgres.domain.repositories.JobRepository
import cs.trade.scheduler.storage.postgres.domain.repositories.JobRollupRepository
import cs.trade.scheduler.storage.postgres.domain.repositories.JobTypePauseRepository
import cs.trade.scheduler.storage.postgres.domain.repositories.OutboxRepository
import cs.trade.scheduler.storage.postgres.domain.repositories.PayloadSchemaRepository
import cs.trade.scheduler.storage.postgres.domain.repositories.RecurringJobRepository
import cs.trade.scheduler.storage.postgres.domain.repositories.WorkerRepository
import cs.trade.scheduler.storage.postgres.infrastructure.repositories.IdempotencyLogRepositoryImpl
import cs.trade.scheduler.storage.postgres.infrastructure.repositories.JobDependencyRepositoryImpl
import cs.trade.scheduler.storage.postgres.infrastructure.repositories.JobEventRepositoryImpl
import cs.trade.scheduler.storage.postgres.infrastructure.repositories.JobRepositoryImpl
import cs.trade.scheduler.storage.postgres.infrastructure.repositories.JobRollupRepositoryImpl
import cs.trade.scheduler.storage.postgres.infrastructure.repositories.JobTypePauseRepositoryImpl
import cs.trade.scheduler.storage.postgres.infrastructure.repositories.OutboxRepositoryImpl
import cs.trade.scheduler.storage.postgres.infrastructure.repositories.PayloadSchemaRepositoryImpl
import cs.trade.scheduler.storage.postgres.infrastructure.repositories.RecurringJobRepositoryImpl
import cs.trade.scheduler.storage.postgres.infrastructure.repositories.WorkerRepositoryImpl
import cs.trade.scheduler.storage.postgres.infrastructure.scheduler.DefaultScheduler
import cs.trade.scheduler.storage.postgres.infrastructure.scheduler.FunctionRefBindingResolver
import cs.trade.scheduler.storage.postgres.infrastructure.scheduler.PostgresIdempotencyStore
import org.flywaydb.core.Flyway
import org.jetbrains.exposed.v1.jdbc.Database
import org.koin.core.module.Module
import org.koin.dsl.module
import javax.sql.DataSource

/**
 * Builder lambda config for the Postgres storage module. See DESIGN.md section 12.2.
 *
 * Usage:
 * ```
 * val ds = HikariDataSource(HikariConfig().apply {
 *     jdbcUrl = "jdbc:postgresql://..."
 *     username = "..."
 *     password = "..."
 *     // REQUIRED: PgJDBC defaults String params to VARCHAR; INSERT into a JSONB column
 *     // (payload_json, context_json) then fails. `unspecified` lets Postgres infer.
 *     addDataSourceProperty("stringtype", "unspecified")
 * })
 *
 * startKoin {
 *     modules(
 *         schedulerPostgresModule {
 *             dataSource = ds
 *             runMigrations = true    // true ONLY in scheduler-infra container
 *         }
 *     )
 * }
 * ```
 */
public class SchedulerPostgresConfig {
    public var dataSource: DataSource? = null
    public var runMigrations: Boolean = false

    /** Schema validation that the user-app does at start (DESIGN.md 14.4). */
    public var failFastOnSchemaMismatch: Boolean = true
}

public fun schedulerPostgresModule(configure: SchedulerPostgresConfig.() -> Unit): Module {
    val config = SchedulerPostgresConfig().apply(configure)
    val dataSource = requireNotNull(config.dataSource) {
        "schedulerPostgresModule: dataSource is required"
    }

    if (config.runMigrations) {
        Flyway.configure()
            .dataSource(dataSource)
            .locations(SCHEDULER_MIGRATION_LOCATION)
            .load()
            .migrate()
    } else if (config.failFastOnSchemaMismatch) {
        // User-app side (runMigrations=false): compare Flyway's bundled migration set
        // against what's applied in the target DB. Mismatch → fail at startup with a
        // clear message rather than the cluster hitting a "column does not exist"
        // exception three days later when someone enqueues an unusual job. See
        // DESIGN.md 14.4.
        val flyway = Flyway.configure().dataSource(dataSource).locations(SCHEDULER_MIGRATION_LOCATION).load()
        val info = flyway.info()
        val pending = info.pending()
        if (pending.isNotEmpty()) {
            val versions = pending.joinToString(", ") { it.version?.toString() ?: it.script }
            error(
                "Schema mismatch (failFastOnSchemaMismatch=true): the user-app embeds " +
                    "Flyway migrations [$versions] that haven't been applied to the database. " +
                    "Run the scheduler-infra container against this DB to migrate, or " +
                    "set failFastOnSchemaMismatch=false to bypass (NOT recommended in prod).",
            )
        }
    }

    return module {
        single<DataSource> { dataSource }
        single<Database> { Database.connect(dataSource) }
        single<JobEventRepository> { JobEventRepositoryImpl(get()) }
        single<JobRepository> { JobRepositoryImpl(get(), get<EventBus>(), get<JobEventRepository>()) }
        single<OutboxRepository> { OutboxRepositoryImpl(get()) }
        single<JobDependencyRepository> { JobDependencyRepositoryImpl(get()) }
        single<RecurringJobRepository> { RecurringJobRepositoryImpl(get()) }
        single<WorkerRepository> { WorkerRepositoryImpl(get()) }
        single<IdempotencyLogRepository> { IdempotencyLogRepositoryImpl(get()) }
        single<JobRollupRepository> { JobRollupRepositoryImpl(get()) }
        single<JobTypePauseRepository> { JobTypePauseRepositoryImpl(get()) }
        single<PayloadSchemaRepository> { PayloadSchemaRepositoryImpl(get()) }
        // Handler-facing dedup primitive. User-apps that want to opt out can override
        // with `single<IdempotencyStore> { IdempotencyStore.Noop }` (Koin last-wins).
        single<IdempotencyStore> {
            PostgresIdempotencyStore(
                repository = get<IdempotencyLogRepository>(),
                metrics = get<cs.trade.scheduler.core.backend.idempotency.IdempotencyMetrics>(),
            )
        }
        single<StorageProvider> {
            PostgresStorageProvider(
                jobs = get(),
                outbox = get(),
                jobDependencies = get(),
                recurringJobs = get(),
                jobEvents = get(),
                workers = get(),
                idempotencyLog = get(),
                jobRollups = get(),
                jobTypePauses = get(),
            )
        }
        // Scheduler impl lives in :storage-postgres (uses storage + Database directly).
        // SchedulerCoreConfig must already be in the graph — added by schedulerCoreModule { }.
        single<FunctionRefBindingResolver> {
            FunctionRefBindingResolver.KoinBacked(getKoin())
        }
        single<Scheduler> {
            DefaultScheduler(
                storage = get(),
                database = get(),
                config = get<SchedulerCoreConfig>(),
                eventBus = get<EventBus>(),
                events = get<JobEventRepository>(),
                contextCapture = get<ContextCapture>(),
                archivalSink = get<ArchivalSink>(),
                functionRefBindingResolver = get<FunctionRefBindingResolver>(),
            )
        }
    }
}
