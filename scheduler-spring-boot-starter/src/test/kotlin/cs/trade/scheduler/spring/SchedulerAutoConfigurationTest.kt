package cs.trade.scheduler.spring

import com.rabbitmq.client.ConnectionFactory
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import cs.trade.scheduler.core.backend.Scheduler
import cs.trade.scheduler.engine.worker.infrastructure.WorkerPool
import cs.trade.scheduler.storage.postgres.domain.repositories.JobRepository
import cs.trade.scheduler.storage.postgres.infrastructure.scheduler.DefaultScheduler
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.koin.core.context.GlobalContext
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.testcontainers.containers.PostgreSQLContainer
import javax.sql.DataSource

/**
 * Wiring tests for [SchedulerAutoConfiguration] driven via [ApplicationContextRunner] —
 * no full Spring Boot app, no `@SpringBootTest`, no port binding. Just verifies that:
 *
 *  1. With minimal `scheduler.postgres.*` props the context exposes [Scheduler],
 *     [JobRepository] and [WorkerPool] beans wired correctly.
 *  2. The concrete `Scheduler` instance is the storage-postgres impl ([DefaultScheduler]).
 *  3. A user-provided `@Bean DataSource` wins over the autoconfig default
 *     (`@ConditionalOnMissingBean(DataSource::class)`).
 *  4. Missing required props (`scheduler.postgres.url`) → the context fails to start
 *     with a binding error pointing at the missing key.
 *
 * **PG provisioning** follows the same `EXTERNAL_PG_URL` env-bypass pattern as
 * [cs.trade.scheduler.engine.worker.JobContextProgressIntegrationTest] — when the env
 * vars are set, we point the autoconfig at the shared `scheduler-test-pg` container
 * (port 5433 in docker-compose). Without the env vars we boot Testcontainers, which
 * costs ~5s but keeps the suite self-contained.
 *
 * **Rabbit:** the autoconfig builds a `ConnectionFactory` from `scheduler.rabbit.*` but
 * doesn't open a connection until `WorkerPool.start()` is called by the lifecycle bean —
 * and the lifecycle is disabled here via `scheduler.worker.auto-start=false`. So these
 * tests assert wiring only; they don't need a live Rabbit. A separate
 * `EXTERNAL_RABBIT_*`-gated suite would be required to exercise the consumer loop.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SchedulerAutoConfigurationTest {

    private companion object {
        private val externalUrl: String? = System.getenv("EXTERNAL_PG_URL")?.takeIf { it.isNotBlank() }
        /**
         * Rabbit env-bypass — matches the pattern used by other suites in the repo.
         * Defaults to `guest/guest` on localhost:5672 when not set; CI exports the
         * scheduler-test-rabbit creds (port 5673, user `scheduler`).
         */
        private val rabbitHost: String = System.getenv("EXTERNAL_RABBIT_HOST")?.takeIf { it.isNotBlank() } ?: "localhost"
        private val rabbitPort: Int = System.getenv("EXTERNAL_RABBIT_PORT")?.toIntOrNull() ?: 5672
        private val rabbitUser: String = System.getenv("EXTERNAL_RABBIT_USER")?.takeIf { it.isNotBlank() } ?: "guest"
        private val rabbitPass: String = System.getenv("EXTERNAL_RABBIT_PASSWORD")?.takeIf { it.isNotBlank() } ?: "guest"

        private lateinit var jdbcUrl: String
        private lateinit var pgUser: String
        private lateinit var pgPass: String
        private var postgres: PostgreSQLContainer<*>? = null

        @BeforeAll
        @JvmStatic
        fun setUpPg() {
            val ext = externalUrl
            if (ext != null) {
                jdbcUrl = ext
                pgUser = System.getenv("EXTERNAL_PG_USER") ?: "scheduler"
                pgPass = System.getenv("EXTERNAL_PG_PASSWORD") ?: "scheduler"
            } else {
                val tc = PostgreSQLContainer("postgres:16-alpine")
                    .withDatabaseName("scheduler")
                    .withUsername("scheduler")
                    .withPassword("scheduler")
                tc.start()
                postgres = tc
                jdbcUrl = tc.jdbcUrl
                pgUser = tc.username
                pgPass = tc.password
            }

            // Migrate once up-front; per-test `scheduler.run-migrations` toggling could
            // race when tests run in parallel, and Flyway is idempotent on a clean DB.
            val ds = HikariDataSource(HikariConfig().apply {
                this.jdbcUrl = SchedulerAutoConfigurationTest.jdbcUrl
                username = pgUser
                password = pgPass
                maximumPoolSize = 2
                addDataSourceProperty("stringtype", "unspecified")
            })
            try {
                Flyway.configure().dataSource(ds).load().migrate()
            } finally {
                ds.close()
            }
        }

        @AfterAll
        @JvmStatic
        fun tearDown() {
            runCatching { postgres?.stop() }
        }
    }

    private fun runner(): ApplicationContextRunner = ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(SchedulerAutoConfiguration::class.java))
        .withPropertyValues(
            "scheduler.postgres.url=$jdbcUrl",
            "scheduler.postgres.user=$pgUser",
            "scheduler.postgres.password=$pgPass",
            // The migrations were applied above; skip on every test so we don't race or
            // double-migrate. failFastOnSchemaMismatch defaults to true in
            // SchedulerPostgresConfig, but pending=0 so it's a no-op.
            "scheduler.run-migrations=false",
            // We're testing wiring only — keep the lifecycle inert so no Rabbit consumer
            // is started. Note: RabbitJobTransport opens a connection eagerly at Koin
            // resolve time (topology declare-on-construct), so we still need valid
            // broker creds even with auto-start=false.
            "scheduler.worker.auto-start=false",
            "scheduler.rabbit.host=$rabbitHost",
            "scheduler.rabbit.port=$rabbitPort",
            "scheduler.rabbit.user=$rabbitUser",
            "scheduler.rabbit.password=$rabbitPass",
            // Don't let two parallel suites share the same node id in the worker registry.
            "scheduler.node-id=spring-test-${System.nanoTime()}",
        )
        // Tests share the JVM-global Koin context — stop any prior instance before each
        // run so SchedulerAutoConfiguration's startKoin doesn't trip the
        // "context already populated" guard.
        .withInitializer { runCatching { GlobalContext.stopKoin() } }

    @Test
    fun `default wiring exposes Scheduler JobRepository and WorkerPool beans`() {
        runner().run { ctx ->
            assertTrue(ctx.containsBean("scheduler"), "Scheduler bean missing")
            assertTrue(ctx.containsBean("jobRepository"), "JobRepository bean missing")
            assertTrue(ctx.containsBean("workerPool"), "WorkerPool bean missing")
            assertTrue(ctx.containsBean("schedulerLifecycle"), "Lifecycle bean missing")
            assertTrue(ctx.containsBean("schedulerKoinContext"), "Koin holder missing")

            val scheduler = ctx.getBean(Scheduler::class.java)
            assertNotNull(scheduler)
            // The Koin storage-postgres module binds Scheduler -> DefaultScheduler. If
            // anyone else binds the interface (last-wins in Koin), this assertion catches
            // the surprise.
            assertTrue(
                scheduler is DefaultScheduler,
                "Expected DefaultScheduler, got ${scheduler.javaClass.name}",
            )
            assertNotNull(ctx.getBean(JobRepository::class.java))
            assertNotNull(ctx.getBean(WorkerPool::class.java))
        }
    }

    @Test
    fun `user-provided DataSource bean overrides the autoconfig default`() {
        val userDs = HikariDataSource(HikariConfig().apply {
            jdbcUrl = SchedulerAutoConfigurationTest.jdbcUrl
            username = pgUser
            password = pgPass
            // Pool size >= 2: Flyway's failFastOnSchemaMismatch info() call may briefly
            // hold one connection while the Koin Postgres module's Exposed Database also
            // wants one, so a size-1 pool deadlocks at startup.
            maximumPoolSize = 4
            poolName = "user-override-pool"
            addDataSourceProperty("stringtype", "unspecified")
        })
        try {
            runner()
                .withBean("userDataSource", DataSource::class.java, { userDs })
                .run { ctx ->
                    val ds = ctx.getBean(DataSource::class.java)
                    assertSame(userDs, ds, "@ConditionalOnMissingBean should have yielded to userDataSource")
                    // The Koin Scheduler is built against the user-provided pool —
                    // simplest way to assert that is to confirm only one DataSource is in
                    // the context (autoconfig's HikariDataSource bean was skipped).
                    val dsBeans = ctx.beanFactory.getBeanNamesForType(DataSource::class.java)
                    assertEquals(1, dsBeans.size, "Autoconfig DS should not have been registered. Beans: ${dsBeans.toList()}")
                }
        } finally {
            userDs.close()
        }
    }

    @Test
    fun `missing scheduler postgres url fails context startup with binding error`() {
        ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(SchedulerAutoConfiguration::class.java))
            // No scheduler.postgres.* props at all — the SchedulerProperties record has
            // a non-null `postgres` param, so binding should reject startup.
            .withInitializer { runCatching { GlobalContext.stopKoin() } }
            .run { ctx ->
                assertNotNull(ctx.startupFailure, "Context should have failed without scheduler.postgres.url")
                // Don't pin the exact message — Spring's binding diagnostics change
                // between minor versions. Just confirm the failure root mentions the
                // missing prefix.
                val msg = buildString {
                    var t: Throwable? = ctx.startupFailure
                    while (t != null) {
                        t.message?.let { appendLine(it) }
                        t = t.cause
                    }
                }
                assertTrue(
                    msg.contains("scheduler") || msg.contains("postgres"),
                    "Failure message should mention the missing prefix; got: $msg",
                )
            }
    }

    /**
     * Smoke-checks that the autoconfig metadata file is on the classpath and points at
     * [SchedulerAutoConfiguration]. Without this resource Spring Boot 3.x won't pick the
     * starter up at all — the rest of the suite would still pass thanks to the explicit
     * `AutoConfigurations.of(...)` registration, masking the bug.
     */
    @Test
    fun `AutoConfiguration imports resource registers SchedulerAutoConfiguration`() {
        val cl = SchedulerAutoConfiguration::class.java.classLoader
        val url = cl.getResource("META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports")
        assertNotNull(url, "AutoConfiguration.imports resource missing from classpath")
        val contents = url!!.readText().trim()
        assertEquals(
            SchedulerAutoConfiguration::class.java.name,
            contents,
            "imports file should contain exactly the SchedulerAutoConfiguration FQN",
        )
    }

}
