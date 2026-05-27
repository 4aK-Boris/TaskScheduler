package cs.trade.scheduler.runner

import com.rabbitmq.client.ConnectionFactory
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import cs.trade.scheduler.core.backend.SchedulerCoreConfig
import cs.trade.scheduler.core.backend.events.EventBus
import cs.trade.scheduler.core.backend.schedulerCoreModule
import cs.trade.scheduler.dashboard.server.api.routes.configureDashboardRouting
import cs.trade.scheduler.dashboard.server.schedulerDashboardModule
import cs.trade.scheduler.engine.infra.infrastructure.leader.LeaderElection
import cs.trade.scheduler.engine.infra.infrastructure.loops.FastForwardTask
import cs.trade.scheduler.engine.infra.infrastructure.loops.OutboxPublisher
import cs.trade.scheduler.engine.infra.infrastructure.loops.RecurringScheduler
import cs.trade.scheduler.engine.infra.infrastructure.loops.RetentionCleanup
import cs.trade.scheduler.engine.infra.infrastructure.loops.SafetyNetPoller
import cs.trade.scheduler.engine.infra.infrastructure.schedulerInfraModule
import cs.trade.scheduler.storage.postgres.domain.repositories.JobRepository
import cs.trade.scheduler.storage.postgres.domain.repositories.OutboxRepository
import cs.trade.scheduler.storage.postgres.domain.repositories.WorkerRepository
import cs.trade.scheduler.storage.postgres.infrastructure.events.PostgresEventBus
import cs.trade.scheduler.storage.postgres.infrastructure.schedulerPostgresModule
import cs.trade.scheduler.transport.rabbit.domain.JobTransport
import cs.trade.scheduler.transport.rabbit.infrastructure.schedulerRabbitModule
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import io.ktor.server.auth.Authentication
import io.ktor.server.auth.UserIdPrincipal
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.basic
import io.ktor.server.auth.jwt.JWTPrincipal
import io.ktor.server.auth.jwt.jwt
import io.ktor.server.engine.embeddedServer
import io.ktor.server.http.content.singlePageApplication
import io.ktor.server.netty.Netty
import io.ktor.server.metrics.micrometer.MicrometerMetrics
import io.ktor.server.plugins.calllogging.CallLogging
import io.ktor.server.plugins.compression.Compression
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets
import io.micrometer.core.instrument.binder.jvm.ClassLoaderMetrics
import io.micrometer.core.instrument.binder.jvm.JvmGcMetrics
import io.micrometer.core.instrument.binder.jvm.JvmMemoryMetrics
import io.micrometer.core.instrument.binder.jvm.JvmThreadMetrics
import io.micrometer.core.instrument.binder.system.ProcessorMetrics
import io.micrometer.prometheusmetrics.PrometheusConfig
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.koin.core.context.GlobalContext
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import org.koin.ktor.plugin.Koin
import org.koin.logger.slf4jLogger
import org.slf4j.LoggerFactory
import javax.sql.DataSource

private val log = LoggerFactory.getLogger("cs.trade.scheduler.runner.Application")

public fun main() {
    val config = RunnerConfig.fromEnv()
    // Fail-fast before any side-effecting setup (Hikari opens TCP connections, Rabbit
    // ConnectionFactory caches DNS). One IllegalStateException listing every problem at
    // once beats N restart cycles fixing one env var at a time.
    config.validate()
    log.info("Starting scheduler-infra (node={}, dashboard port={})", config.nodeId, config.dashboardPort)

    val dataSource = buildDataSource(config)
    val rabbitFactory = buildRabbitFactory(config)
    val metricsRegistry = buildMetricsRegistry()

    // Multi-replica dashboard requires cross-process event delivery: same-JVM events
    // hit local subscribers immediately, NOTIFY fans out to other replicas' listeners.
    // Overrides the in-memory default from schedulerCoreModule (Koin last-wins).
    val postgresEventBus = PostgresEventBus(
        jdbcUrl = config.postgresUrl,
        jdbcUser = config.postgresUser,
        jdbcPassword = config.postgresPassword,
        nodeId = config.nodeId,
        json = SchedulerCoreConfig().json,  // same defaults as schedulerCoreModule binds
    )

    startKoin {
        slf4jLogger()
        modules(
            schedulerCoreModule { nodeId = config.nodeId },
            schedulerPostgresModule {
                this.dataSource = dataSource
                runMigrations = config.runMigrations
            },
            schedulerRabbitModule {
                connectionFactory = rabbitFactory
                queues = listOf("default", "email", "heavy")
            },
            schedulerInfraModule { /* DESIGN.md 18.3 defaults */ },
            schedulerDashboardModule {
                port = config.dashboardPort
                if (config.dashboardAuthPassword != null) {
                    auth {
                        basic {
                            username = config.dashboardAuthUser
                            password = config.dashboardAuthPassword
                        }
                    }
                } else {
                    log.warn("DASHBOARD_AUTH_PASSWORD not set — running dashboard without auth (local dev only)")
                }
            },
            module { single<EventBus> { postgresEventBus } },
        )
    }

    val koin = GlobalContext.get()

    // Start the 5 infra background loops on a single SupervisorJob scope — one DB or
    // Rabbit blip in one loop must not kill the others.
    val loopsScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // Start the PG LISTEN/NOTIFY event bus — every replica listens, so a dashboard
    // client connected to ANY replica gets events emitted on ANY other. Listener
    // self-heals on connection loss (RECONNECT_DELAY_MS).
    postgresEventBus.start(loopsScope)

    // Multi-replica leader election (DESIGN.md 14.3). Single replica → always leader;
    // multi-replica → only one process runs the heavy loops at a time. Uses a dedicated
    // raw JDBC connection (not the Hikari pool) to hold the advisory lock long-term.
    val leader = LeaderElection(
        jdbcUrl = config.postgresUrl,
        jdbcUser = config.postgresUser,
        jdbcPassword = config.postgresPassword,
    )
    leader.start(loopsScope)
    val isLeader: () -> Boolean = leader::isCurrentLeader

    koin.get<OutboxPublisher>().start(loopsScope, intervalMillis = 100L, isLeader = isLeader)
    koin.get<SafetyNetPoller>().start(loopsScope, isLeader = isLeader)
    koin.get<FastForwardTask>().start(loopsScope, isLeader = isLeader)
    koin.get<RecurringScheduler>().start(loopsScope, isLeader = isLeader)
    koin.get<RetentionCleanup>().start(loopsScope, isLeader = isLeader)
    log.info("All infra loops started (outbox / safety-net / fast-forward / recurring / retention)")

    // Custom Prometheus gauges — populated by a 15s poll loop. Gated by leadership too:
    // running N COUNT queries every 15s on each replica is wasteful. Followers' gauges
    // stay at their last value; Prometheus typically scrapes only one replica anyway
    // via target labels.
    SchedulerMetricsBinder(
        registry = metricsRegistry,
        jobs = koin.get<JobRepository>(),
        outbox = koin.get<OutboxRepository>(),
        workers = koin.get<WorkerRepository>(),
    ).start(loopsScope, isLeader = isLeader)

    val server = embeddedServer(Netty, port = config.dashboardPort) {
        configureKtor(
            authPassword = config.dashboardAuthPassword,
            jwtSecret = config.dashboardJwtSecret,
            jwtIssuer = config.dashboardJwtIssuer,
            jwtAudience = config.dashboardJwtAudience,
            dataSource = dataSource,
            rabbitFactory = rabbitFactory,
            metricsRegistry = metricsRegistry,
            leader = leader,
        )
    }

    Runtime.getRuntime().addShutdownHook(Thread {
        log.info("Shutdown hook: stopping Ktor server, loops, Rabbit, DataSource")
        runCatching { server.stop(gracePeriodMillis = 3_000, timeoutMillis = 10_000) }
        runCatching { loopsScope.cancel() }
        // Release advisory lock explicitly. PG releases it on connection close anyway,
        // but explicit unlock lets the next replica pick up faster (no waiting for the
        // dead replica's TCP timeout to fire).
        runCatching { leader.release() }
        runCatching { runBlocking { koin.get<JobTransport>().close() } }
        runCatching { stopKoin() }
        runCatching { (dataSource as? HikariDataSource)?.close() }
        // Closeable JVM binders (JvmGcMetrics registers a Notification listener) live
        // on the registry — closing it releases them.
        runCatching { metricsRegistry.close() }
        log.info("Shutdown complete")
    })

    server.start(wait = true)
}

private fun Application.configureKtor(
    authPassword: String?,
    jwtSecret: String?,
    jwtIssuer: String?,
    jwtAudience: String?,
    dataSource: DataSource,
    rabbitFactory: ConnectionFactory,
    metricsRegistry: PrometheusMeterRegistry,
    leader: LeaderElection,
) {
    install(ContentNegotiation) { json() }
    install(Compression)
    install(CallLogging)
    install(WebSockets)
    install(MicrometerMetrics) {
        registry = metricsRegistry
        // Per-route ktor.http.server.requests timer is the headline metric; per-status
        // counters come from the same plugin. JVM binders are wired once at registry
        // creation time so memory / GC / threads stay scrapeable.
    }
    install(Koin) {
        slf4jLogger()
        // Modules are already loaded via startKoin in main(); the plugin just exposes
        // the GlobalContext to Ktor's inject<>() helpers used by route files.
    }
    val authMethods = mutableListOf<String>()
    if (authPassword != null || jwtSecret != null) {
        install(Authentication) {
            // BasicAuth — same shape as before; opt-in via DASHBOARD_AUTH_PASSWORD.
            if (authPassword != null) {
                basic("dashboard") {
                    realm = "TaskScheduler dashboard"
                    validate { creds ->
                        if (creds.password == authPassword) UserIdPrincipal(creds.name) else null
                    }
                }
                authMethods += "dashboard"
            }
            // JWT — symmetric HMAC256 with the configured secret. `iss` and `aud` claims
            // are validated when set. Token subject (`sub`) becomes the principal name
            // for audit attribution. We install JWT in addition to (not instead of) Basic
            // so service-to-service traffic can use bearer tokens while operators keep
            // using the BasicAuth prompt from the SPA.
            if (jwtSecret != null) {
                jwt("jwt") {
                    realm = "TaskScheduler dashboard"
                    val verifier = JWT.require(Algorithm.HMAC256(jwtSecret))
                        .apply {
                            if (jwtIssuer != null) withIssuer(jwtIssuer)
                            if (jwtAudience != null) withAudience(jwtAudience)
                        }
                        .build()
                    verifier(verifier)
                    validate { creds ->
                        // Require a subject — otherwise audit attribution falls back to
                        // "anonymous" which defeats the purpose of bearer auth.
                        val sub = creds.payload.subject
                        if (sub.isNullOrBlank()) null else JWTPrincipal(creds.payload)
                    }
                }
                authMethods += "jwt"
            }
        }
    }

    routing {
        // Operational endpoints — k8s probes + Prometheus scrapers don't carry BasicAuth
        // and must not need it. Mount BEFORE the auth-wrapped block so they're public
        // regardless of dashboard auth config.
        configureHealthRouting(dataSource, rabbitFactory, leader)
        configureMetricsRouting(metricsRegistry)

        if (authMethods.isNotEmpty()) {
            // Multi-method wrap: a request that presents EITHER credential type passes.
            // Ktor tries methods in declaration order; the first one that produces a
            // principal wins. Covers REST AND the /api/ws/events WebSocket — see
            // EventsRouting.kt for the browser-cached-creds caveat.
            authenticate(*authMethods.toTypedArray()) { configureDashboardRouting() }
        } else {
            configureDashboardRouting()
        }
        // SPA fallback for the wasm bundle. /api/* routes above win because they're
        // registered first; everything else falls through to index.html so the client-
        // side router can take over.
        //
        // NOTE: index.html is served without auth so browsers can fetch it and present
        // the BasicAuth prompt for /api/* on demand. If we ever need to keep the bundle
        // itself private, wrap singlePageApplication in authenticate("dashboard") too.
        singlePageApplication {
            useResources = true
            filesPath = "dashboard-web"
            defaultPage = "index.html"
        }
    }
}

private fun buildMetricsRegistry(): PrometheusMeterRegistry {
    val registry = PrometheusMeterRegistry(PrometheusConfig.DEFAULT)
    // JVM-side binders: memory pools, GC pauses, thread counts, processor stats,
    // classloader counts. All bound once at startup — Micrometer keeps them refreshed.
    JvmMemoryMetrics().bindTo(registry)
    JvmGcMetrics().bindTo(registry)
    JvmThreadMetrics().bindTo(registry)
    ProcessorMetrics().bindTo(registry)
    ClassLoaderMetrics().bindTo(registry)
    return registry
}

private fun buildDataSource(config: RunnerConfig): DataSource =
    HikariDataSource(
        HikariConfig().apply {
            jdbcUrl = config.postgresUrl
            username = config.postgresUser
            password = config.postgresPassword
            maximumPoolSize = 10
            // Required for JSONB columns — see DESIGN.md 14.2 / schedulerPostgresModule KDoc.
            addDataSourceProperty("stringtype", "unspecified")
        }
    )

private fun buildRabbitFactory(config: RunnerConfig): ConnectionFactory =
    ConnectionFactory().apply {
        host = config.rabbitHost
        port = config.rabbitPort
        username = config.rabbitUser
        password = config.rabbitPassword
        virtualHost = config.rabbitVhost
        isAutomaticRecoveryEnabled = true
        networkRecoveryInterval = 5_000L
        requestedHeartbeat = 30
    }
