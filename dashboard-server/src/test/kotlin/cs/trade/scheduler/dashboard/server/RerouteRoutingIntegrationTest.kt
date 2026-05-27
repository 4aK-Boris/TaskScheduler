@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package cs.trade.scheduler.dashboard.server

import cs.trade.scheduler.core.backend.Scheduler
import cs.trade.scheduler.core.backend.handler.Job
import cs.trade.scheduler.dashboard.server.api.routes.configureDashboardRouting
import cs.trade.scheduler.shared.JobState
import cs.trade.scheduler.shared.RerouteResult
import cs.trade.scheduler.shared.dto.RerouteJobResponse
import cs.trade.scheduler.storage.postgres.domain.repositories.JobRepository
import cs.trade.scheduler.storage.postgres.infrastructure.tables.OutboxTable
import io.ktor.client.request.post
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.suspendTransaction
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.koin.ktor.plugin.Koin
import kotlin.uuid.Uuid

/**
 * End-to-end REST coverage for `POST /api/jobs/{id}/reroute` (DESIGN.md 22.2).
 *
 * Each test drives the full stack:
 *   Ktor `testApplication` (HTTP layer)
 *     → `JobsRouting.configureJobsRouting()` (routing layer)
 *     → `RerouteJobUseCase` (use-case layer, injected by Koin)
 *     → `DefaultScheduler.reroute` (domain layer)
 *     → `JobRepository.updateRouting` + `OutboxRepository.insert` (storage layer)
 *     → real Postgres.
 *
 * No mocks below `Scheduler` — that's the whole point of this file. Unit-level CAS
 * semantics are already covered by `JobRepositoryCasIntegrationTest` in
 * `:storage-postgres`; here we prove the wiring (HTTP status mapping, body shape,
 * outbox-row routing key, target column writes) matches what the dashboard frontend
 * consumes.
 *
 * Lifecycle: manual (no `@Testcontainers` annotation) so `EXTERNAL_PG_URL` can bypass
 * the daemon API. Koin is started ONCE per class — every test re-enqueues a fresh job
 * with a random `Uuid`, so cases never alias on the same DB row.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RerouteRoutingIntegrationTest {

    /**
     * Distinct payload type per test file so other dashboard tests in the same class-
     * loader don't pick up our jobs in their `findAll`-style queries (defence in depth —
     * we filter by jobId everywhere, but a payload-type collision tends to bite in
     * `JobApiMapper` when an old test's row shows up unexpectedly).
     */
    @Serializable
    data class RerouteTestPayload(val n: Long) : Job

    private lateinit var storage: DashboardTestSupport.Storage
    private val json = Json { ignoreUnknownKeys = true }

    @BeforeAll
    fun setUp() {
        storage = DashboardTestSupport.startStorage()
    }

    @AfterAll
    fun tearDown() {
        DashboardTestSupport.stopKoinSafe()
        storage.shutdown()
    }

    /**
     * Boots a minimal Ktor app with only the dashboard routing wired — health/metrics/
     * SPA aren't relevant to reroute coverage. The Koin Ktor plugin OWNS GlobalContext
     * here: it stops any previous Koin app and starts one populated with our test
     * modules. After the plugin is installed, [DashboardTestSupport.resolve] can pull
     * Scheduler/JobRepository/Database for assertion-side reads.
     *
     * `startApplication()` forces the application config block to run synchronously
     * BEFORE the test body — without it, Ktor lazy-defers config until the first HTTP
     * request, which means `DashboardTestSupport.resolve()` calls inside the block
     * would race with the Koin plugin install and hit "scope is closed" from the
     * previous test's app teardown.
     *
     * WebSockets must be installed because `EventsRouting.kt` (mounted by
     * `configureDashboardRouting()`) declares `webSocket("/api/ws/events")`. We don't
     * exercise it here, but the route registration itself requires the plugin or the
     * routing-block install panics with `MissingApplicationPluginException`.
     */
    private fun rerouteTestApp(block: suspend (io.ktor.client.HttpClient) -> Unit) = testApplication {
        application {
            install(ContentNegotiation) { json() }
            install(io.ktor.server.websocket.WebSockets)
            install(Koin) {
                modules(*DashboardTestSupport.dashboardModules(storage.dataSource, nodeId = "test-reroute"))
            }
            routing { configureDashboardRouting() }
        }
        startApplication()
        block(this.client)
    }

    @Test
    fun `POST reroute on ENQUEUED row returns 200 REROUTED and writes target_node + new outbox row`() = runBlocking {
        rerouteTestApp { client ->
            // After the Ktor Koin plugin installed our modules, GlobalContext is live —
            // pull Scheduler / JobRepository / Database for the seed + verification.
            val scheduler: Scheduler = DashboardTestSupport.resolve()
            val jobs: JobRepository = DashboardTestSupport.resolve()
            val database: Database = DashboardTestSupport.resolve()

            // Enqueue via the real scheduler — same code path the user-app uses, so we
            // also exercise the side-effect that puts an INITIAL outbox row in the table
            // (we'll later assert the reroute INSERTED a SECOND row with `node.node-a`).
            val jobId = scheduler.enqueue(RerouteTestPayload(1L))
            val before = jobs.findById(jobId)
            assertNotNull(before, "enqueue must produce a row")
            assertEquals(JobState.ENQUEUED, before!!.state)
            assertNull(before.targetNode, "fresh enqueue has no target_node")

            val response = client.post("/api/jobs/$jobId/reroute?targetNode=node-a&by=alice")
            // 200 with body { jobId, result=REROUTED } is what the frontend's
            // RerouteJobApiCall.kt expects — anything else and the dashboard would
            // throw a deserialisation error.
            assertEquals(HttpStatusCode.OK, response.status, "200 == REROUTED per JobsRouting status mapping")
            val body: RerouteJobResponse = json.decodeFromString(response.bodyAsText())
            assertEquals(jobId.toString(), body.jobId)
            assertEquals(RerouteResult.REROUTED, body.result)

            // Storage assertions — the use-case promises (a) targetNode written, (b) a NEW
            // outbox row exists with routing key `node.node-a`. We can't just assert "more
            // than one outbox row" because a future change might coalesce inserts, so we
            // check the routing key explicitly.
            val after = jobs.findById(jobId)!!
            assertEquals("node-a", after.targetNode, "target_node CAS must have applied")
            assertNull(after.targetTag, "we only sent targetNode — targetTag stays null")
            assertEquals(before.version + 1, after.version, "reroute bumps version by exactly 1")

            val rerouteOutboxRows = withContext(Dispatchers.IO) {
                suspendTransaction(db = database) {
                    OutboxTable.selectAll()
                        .where { (OutboxTable.jobId eq jobId) and (OutboxTable.routingKey eq "node.node-a") }
                        .toList()
                }
            }
            assertEquals(
                1, rerouteOutboxRows.size,
                "exactly one new outbox row with routing key `node.node-a` (the original " +
                    "enqueue used the default queue, so this row is distinct)",
            )
        }
    }

    @Test
    fun `POST reroute on terminal row returns 409 ALREADY_TERMINAL and leaves targets null`() = runBlocking {
        rerouteTestApp { client ->
            val scheduler: Scheduler = DashboardTestSupport.resolve()
            val jobs: JobRepository = DashboardTestSupport.resolve()

            val jobId = scheduler.enqueue(RerouteTestPayload(2L))
            // Force terminal via the same primitive the worker uses on success — bypasses
            // the actual handler (we don't have a worker pool in this test). `transitionState`
            // takes the row's current version, bumps it, and flips state atomically.
            val current = jobs.findById(jobId)!!
            assertTrue(
                jobs.transitionState(
                    id = jobId,
                    expectedVersion = current.version,
                    newState = JobState.SUCCEEDED,
                    lockedBy = null,
                    lockedUntilMillis = null,
                ),
                "transitionState must succeed — version matches and SUCCEEDED is a legal target",
            )

            val response = client.post("/api/jobs/$jobId/reroute?targetNode=node-a&by=alice")
            // 409 is the routing-layer status for ALREADY_TERMINAL — see the `when`
            // in JobsRouting.kt. The body still carries the structured result so the
            // frontend can show "this job already completed" without re-parsing the
            // status code.
            assertEquals(HttpStatusCode.Conflict, response.status)
            val body: RerouteJobResponse = json.decodeFromString(response.bodyAsText())
            assertEquals(RerouteResult.ALREADY_TERMINAL, body.result)

            val after = jobs.findById(jobId)!!
            assertEquals(JobState.SUCCEEDED, after.state, "terminal state unchanged")
            assertNull(after.targetNode, "no target write must have leaked through")
            assertNull(after.targetTag)
        }
    }

    @Test
    fun `POST reroute on unknown jobId returns 404 NOT_FOUND`() = runBlocking {
        // Random Uuid — vanishingly small chance of collision with a real row even
        // across all test classes running against the shared external PG.
        val ghost = Uuid.random()

        rerouteTestApp { client ->
            val response = client.post("/api/jobs/$ghost/reroute?targetNode=node-x&by=alice")
            // 404 must be the status (frontend distinguishes "no row" from "row exists
            // but terminal"). Body still carries the typed result so the UI can render
            // a friendly message instead of a generic "404 page".
            assertEquals(HttpStatusCode.NotFound, response.status)
            val body: RerouteJobResponse = json.decodeFromString(response.bodyAsText())
            assertEquals(ghost.toString(), body.jobId)
            assertEquals(RerouteResult.NOT_FOUND, body.result)
        }
    }
}

