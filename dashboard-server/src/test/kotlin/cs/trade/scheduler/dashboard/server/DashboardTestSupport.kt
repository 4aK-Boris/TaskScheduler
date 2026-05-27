@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package cs.trade.scheduler.dashboard.server

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import cs.trade.scheduler.core.backend.events.EventBus
import cs.trade.scheduler.core.backend.events.InMemoryEventBus
import cs.trade.scheduler.core.backend.schedulerCoreModule
import cs.trade.scheduler.storage.postgres.infrastructure.schedulerPostgresModule
import org.flywaydb.core.Flyway
import org.koin.core.context.GlobalContext
import org.koin.core.context.stopKoin
import org.koin.core.module.Module
import org.koin.dsl.module
import org.testcontainers.containers.PostgreSQLContainer

/**
 * Shared bootstrap for dashboard-server end-to-end REST tests.
 *
 * Each test class spins up a fresh Postgres (testcontainers) OR re-uses an external one
 * via `EXTERNAL_PG_URL`. The external path is what runs on this dev box — Docker Desktop
 * blocks the testcontainers daemon API for non-CLI clients, so the user runs a
 * long-lived `postgres:16-alpine` on localhost:5433 and threads it in via env vars.
 * See `JobRepositoryCasIntegrationTest` for the canonical pattern this mirrors.
 *
 * Why we don't reuse `schedulerInfraModule` / `schedulerRabbitModule`: those wire the
 * outbox publisher + rabbit transport. The dashboard REST surface NEVER calls those
 * — `RerouteJobUseCase` writes the outbox row directly via `DefaultScheduler.reroute`
 * and the test then SELECTs the row to verify the routing key. Skipping the publisher
 * keeps the test deterministic (no race with a `markPublished` that would otherwise
 * tick the row to `published_at IS NOT NULL` mid-assertion) AND lets us run without a
 * Rabbit broker in the dev env.
 */
internal object DashboardTestSupport {

    /** Resolved on first access; null = fallback to testcontainers. */
    val externalUrl: String? = System.getenv("EXTERNAL_PG_URL")?.takeIf { it.isNotBlank() }

    /**
     * Build a Hikari pool against either the external PG or a freshly-started
     * testcontainer. The caller owns lifecycle for both — call [shutdown] on AfterAll.
     * Flyway migrate is idempotent so back-to-back test runs against the same external
     * DB don't error.
     */
    fun startStorage(): Storage {
        val jdbcUrl: String
        val user: String
        val pass: String
        var container: PostgreSQLContainer<*>? = null
        if (externalUrl != null) {
            jdbcUrl = externalUrl
            user = System.getenv("EXTERNAL_PG_USER") ?: "scheduler"
            pass = System.getenv("EXTERNAL_PG_PASSWORD") ?: "scheduler"
        } else {
            val tc = PostgreSQLContainer("postgres:16-alpine")
                .withDatabaseName("scheduler")
                .withUsername("scheduler")
                .withPassword("scheduler")
            tc.start()
            container = tc
            jdbcUrl = tc.jdbcUrl
            user = tc.username
            pass = tc.password
        }
        val dataSource = HikariDataSource(HikariConfig().apply {
            this.jdbcUrl = jdbcUrl
            username = user
            password = pass
            maximumPoolSize = 6
            // Required for JSONB columns — same comment as SchedulerPostgresModule.
            addDataSourceProperty("stringtype", "unspecified")
        })
        // Migrate idempotently. We can't rely on `schedulerPostgresModule { runMigrations
        // = true }` alone because the module's failFastOnSchemaMismatch=true path triggers
        // when runMigrations=false against an out-of-date schema — we want migrations
        // applied unconditionally for tests.
        Flyway.configure().dataSource(dataSource).load().migrate()
        return Storage(dataSource, container, jdbcUrl, user, pass)
    }

    data class Storage(
        val dataSource: HikariDataSource,
        val container: PostgreSQLContainer<*>?,
        val jdbcUrl: String,
        val pgUser: String,
        val pgPass: String,
    ) {
        fun shutdown() {
            runCatching { dataSource.close() }
            runCatching { container?.stop() }
        }
    }

    /**
     * The full set of Koin modules tests need: core config + storage + dashboard usecases
     * + an [InMemoryEventBus] override. Returned as a vararg-friendly array because the
     * Koin Ktor plugin's `modules(...)` overload only accepts spread varargs, and these
     * tests install the plugin per-application rather than via global `startKoin`.
     *
     * Why per-application instead of global: the Koin Ktor plugin (4.2.1) `stopKoin() +
     * startKoin(pluginConfig)` REPLACES any pre-existing GlobalContext at plugin install
     * time. So calling `startKoin { modules(...) }` before `testApplication { install(Koin) }`
     * wipes our modules. Instead we feed the same modules to the plugin and let it own
     * the graph for the test app's lifetime.
     *
     * The InMemoryEventBus override is deliberate even though `schedulerCoreModule`
     * already binds one — last-wins means the explicit test override is unambiguous.
     */
    fun dashboardModules(
        dataSource: HikariDataSource,
        nodeId: String,
    ): Array<Module> = arrayOf(
        schedulerCoreModule { this.nodeId = nodeId },
        schedulerPostgresModule {
            this.dataSource = dataSource
            // Migration ran in startStorage(); failFastOnSchemaMismatch must be false
            // because the module's check would re-scan the same schema and we don't
            // care about strict equivalence here.
            this.runMigrations = false
            this.failFastOnSchemaMismatch = false
        },
        schedulerDashboardModule { port = 0 /* unused in test host */ },
        module { single<EventBus> { InMemoryEventBus() } },
    )

    /**
     * Pull a service from the currently-installed Koin GlobalContext. The Koin Ktor
     * plugin populates GlobalContext at plugin install time, so this is safe to call
     * AFTER the testApplication has booted at least once.
     */
    inline fun <reified T : Any> resolve(): T = GlobalContext.get().get()

    /** Defensive teardown: stop any Koin graph the Ktor plugin left behind so
     *  back-to-back test classes don't trip on each other. */
    fun stopKoinSafe() {
        runCatching { stopKoin() }
    }
}
