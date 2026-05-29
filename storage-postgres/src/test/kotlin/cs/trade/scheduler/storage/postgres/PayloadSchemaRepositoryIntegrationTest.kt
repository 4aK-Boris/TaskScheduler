@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package cs.trade.scheduler.storage.postgres

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import cs.trade.scheduler.storage.postgres.infrastructure.repositories.PayloadSchemaRepositoryImpl
import kotlinx.coroutines.runBlocking
import org.flywaydb.core.Flyway
import org.jetbrains.exposed.v1.jdbc.Database
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.testcontainers.containers.PostgreSQLContainer
import kotlin.uuid.Uuid

/**
 * `payload_schema` drift bookkeeping (DESIGN.md 22.9). Verifies the three `recordAndDetect`
 * branches against real Postgres + the V5 migration. Honours `EXTERNAL_PG_URL` (the shared
 * scheduler-test-pg) with a Testcontainers fallback, like the sibling storage tests.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PayloadSchemaRepositoryIntegrationTest {

    private companion object {
        private val externalUrl: String? = System.getenv("EXTERNAL_PG_URL")?.takeIf { it.isNotBlank() }
    }

    private lateinit var dataSource: HikariDataSource
    private lateinit var repo: PayloadSchemaRepositoryImpl
    private var postgres: PostgreSQLContainer<*>? = null

    @BeforeAll
    fun setUp() {
        val jdbcUrl: String; val user: String; val pass: String
        if (externalUrl != null) {
            jdbcUrl = externalUrl
            user = System.getenv("EXTERNAL_PG_USER") ?: "scheduler"
            pass = System.getenv("EXTERNAL_PG_PASSWORD") ?: "scheduler"
        } else {
            val tc = PostgreSQLContainer("postgres:16-alpine")
                .withDatabaseName("scheduler").withUsername("scheduler").withPassword("scheduler")
            tc.start()
            postgres = tc
            jdbcUrl = tc.jdbcUrl; user = tc.username; pass = tc.password
        }
        dataSource = HikariDataSource(HikariConfig().apply {
            this.jdbcUrl = jdbcUrl
            username = user
            password = pass
            maximumPoolSize = 3
        })
        Flyway.configure().dataSource(dataSource).load().migrate()
        repo = PayloadSchemaRepositoryImpl(Database.connect(dataSource))
    }

    @AfterAll
    fun tearDown() {
        runCatching { dataSource.close() }
        runCatching { postgres?.stop() }
    }

    @Test
    fun `first-seen then unchanged then drift then settle`() = runBlocking {
        // Unique type per run so the shared external PG doesn't carry state between runs.
        val type = "cs.trade.test.PayloadX_${Uuid.random()}"

        val firstSeen = repo.recordAndDetect(type, "hashA")
        assertFalse(firstSeen.changed, "first sighting is not a drift")
        assertNull(firstSeen.previousHash)

        val same = repo.recordAndDetect(type, "hashA")
        assertFalse(same.changed, "identical hash is not a drift")
        assertEquals("hashA", same.previousHash)

        val drift = repo.recordAndDetect(type, "hashB")
        assertTrue(drift.changed, "a different hash is a drift")
        assertEquals("hashA", drift.previousHash, "drift reports the prior hash")

        val settled = repo.recordAndDetect(type, "hashB")
        assertFalse(settled.changed, "the new hash is now the recorded one")
        assertEquals("hashB", settled.previousHash)
    }
}
