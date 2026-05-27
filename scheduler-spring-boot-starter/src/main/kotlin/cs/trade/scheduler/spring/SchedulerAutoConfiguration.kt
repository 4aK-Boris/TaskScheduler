package cs.trade.scheduler.spring

import com.rabbitmq.client.ConnectionFactory
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import cs.trade.scheduler.core.backend.Scheduler
import cs.trade.scheduler.core.backend.schedulerCoreModule
import cs.trade.scheduler.engine.worker.infrastructure.WorkerPool
import cs.trade.scheduler.engine.worker.infrastructure.schedulerWorkerModule
import cs.trade.scheduler.storage.postgres.domain.repositories.JobRepository
import cs.trade.scheduler.storage.postgres.infrastructure.schedulerPostgresModule
import cs.trade.scheduler.transport.rabbit.infrastructure.schedulerRabbitModule
import org.koin.core.context.GlobalContext
import org.koin.core.context.startKoin
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import javax.sql.DataSource

/**
 * Spring Boot autoconfiguration that boots the scheduler with a single dependency:
 *
 * ```kotlin
 * implementation("cs.trade:scheduler-spring-boot-starter")
 * ```
 *
 * Registered through Spring Boot 3.x's `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`
 * file. User can override any bean by declaring their own `@Bean` of the same type —
 * `@ConditionalOnMissingBean` on the autoconfig's DataSource and ConnectionFactory beans
 * makes them yield to user overrides. The Koin context is then built against whichever
 * instances Spring resolved.
 *
 * **What gets exposed as Spring beans:**
 *  - [DataSource] (HikariCP) — wired from `scheduler.postgres.*`
 *  - [ConnectionFactory] (RabbitMQ) — wired from `scheduler.rabbit.*`
 *  - [SchedulerKoinHolder] — owns the Koin instance; closed on context shutdown
 *  - [Scheduler] — the user-facing facade (enqueue, scheduleAt, recurring, …)
 *  - [JobRepository] — read-side access for dashboards / introspection
 *  - [WorkerPool] — direct handle (rarely needed; lifecycle is managed)
 *  - [SchedulerLifecycle] — Spring SmartLifecycle bean that starts/stops the worker pool
 *
 * **Single Koin context per JVM.** `startKoin {}` is global state — see
 * [SchedulerKoinHolder] KDoc.
 */
@AutoConfiguration
@EnableConfigurationProperties(SchedulerProperties::class)
public open class SchedulerAutoConfiguration {

    /**
     * Default DataSource — HikariCP with `stringtype=unspecified` (required for JSONB
     * inserts; see `SchedulerPostgresConfig` KDoc in :storage-postgres). User can
     * override by declaring their own `@Bean DataSource` — the Koin Postgres module
     * accepts any `DataSource`, but it must be a `HikariDataSource` if you want the
     * standalone-runner pool-stats binder to attach later. Most users keep this default.
     */
    @Bean(destroyMethod = "close")
    @ConditionalOnMissingBean(DataSource::class)
    public open fun schedulerDataSource(props: SchedulerProperties): HikariDataSource =
        HikariDataSource(
            HikariConfig().apply {
                jdbcUrl = props.postgres.url
                username = props.postgres.user
                password = props.postgres.password
                maximumPoolSize = props.postgres.maxPoolSize
                addDataSourceProperty("stringtype", "unspecified")
            },
        )

    /**
     * Default Rabbit ConnectionFactory with automatic recovery enabled — matches the
     * `buildRabbitFactory` config in `standalone-runner/Application.kt`. Override by
     * declaring `@Bean ConnectionFactory`.
     */
    @Bean
    @ConditionalOnMissingBean(ConnectionFactory::class)
    public open fun schedulerRabbitFactory(props: SchedulerProperties): ConnectionFactory =
        ConnectionFactory().apply {
            host = props.rabbit.host
            port = props.rabbit.port
            username = props.rabbit.user
            password = props.rabbit.password
            virtualHost = props.rabbit.vhost
            isAutomaticRecoveryEnabled = true
            networkRecoveryInterval = 5_000L
            requestedHeartbeat = 30
        }

    /**
     * Boots the Koin context with all four scheduler modules and stashes the resulting
     * `Koin` instance in a [SchedulerKoinHolder] bean. Spring closes the holder on
     * context shutdown, which calls `stopKoin()`.
     *
     * Receives `DataSource` and `ConnectionFactory` as parameters so a user override
     * (their own `@Bean DataSource`) feeds straight into the Koin graph. We require the
     * DataSource to be a `HikariDataSource` because the Koin Postgres module expects one;
     * a non-Hikari override would need to construct its own Koin module.
     */
    @Bean(destroyMethod = "close")
    public open fun schedulerKoinContext(
        dataSource: DataSource,
        rabbitFactory: ConnectionFactory,
        props: SchedulerProperties,
    ): SchedulerKoinHolder {
        // Guard against a left-over Koin from a prior context (common in integration
        // tests that boot multiple ApplicationContexts back-to-back). The runCatching
        // is inside the holder; here we just check whether a context already exists
        // and stop it preemptively.
        runCatching { GlobalContext.stopKoin() }

        val koin = startKoin {
            modules(
                schedulerCoreModule { nodeId = props.nodeId },
                schedulerPostgresModule {
                    this.dataSource = dataSource
                    runMigrations = props.runMigrations
                },
                schedulerRabbitModule {
                    connectionFactory = rabbitFactory
                    queues = props.worker.queues
                },
                schedulerWorkerModule {
                    nodeId = props.nodeId
                    defaultConcurrency = props.worker.defaultConcurrency
                    props.worker.queues.forEach { queue(it) }
                },
            )
        }.koin
        return SchedulerKoinHolder(koin)
    }

    /**
     * Public scheduler facade. Inject this into your `@Service` / `@RestController`
     * classes — it's the only bean most users will reference.
     */
    @Bean
    public open fun scheduler(holder: SchedulerKoinHolder): Scheduler =
        holder.koin.get<Scheduler>()

    /**
     * Read-side repository — useful for custom dashboards or admin endpoints that
     * want to inspect job state without going through the dashboard SPA.
     */
    @Bean
    public open fun jobRepository(holder: SchedulerKoinHolder): JobRepository =
        holder.koin.get<JobRepository>()

    /**
     * Direct worker pool handle. Usually managed by [SchedulerLifecycle]; exposed for
     * advanced cases (e.g. delaying worker startup until after some app-level warmup).
     */
    @Bean
    public open fun workerPool(holder: SchedulerKoinHolder): WorkerPool =
        holder.koin.get<WorkerPool>()

    @Bean
    public open fun schedulerLifecycle(
        workerPool: WorkerPool,
        props: SchedulerProperties,
    ): SchedulerLifecycle = SchedulerLifecycle(workerPool, autoStart = props.worker.autoStart)
}
