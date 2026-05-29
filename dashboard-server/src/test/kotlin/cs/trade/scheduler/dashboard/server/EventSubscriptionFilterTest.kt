@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package cs.trade.scheduler.dashboard.server

import cs.trade.scheduler.core.backend.SchedulerCoreConfig
import cs.trade.scheduler.core.backend.events.EventBus
import cs.trade.scheduler.dashboard.server.api.routes.configureDashboardRouting
import cs.trade.scheduler.shared.JobState
import cs.trade.scheduler.shared.events.EventFilter
import cs.trade.scheduler.shared.events.WebSocketEvent
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.koin.ktor.plugin.Koin
import kotlin.time.Instant
import kotlin.uuid.Uuid
import io.ktor.client.plugins.websocket.WebSockets as ClientWebSockets
import io.ktor.client.plugins.websocket.webSocket as clientWebSocket
import io.ktor.server.websocket.WebSockets as ServerWebSockets

/**
 * Subscribe-with-query on `/api/ws/events` (DESIGN.md 9.2). Two layers:
 *  - pure [EventFilter.matches] cases (the filter algebra — conjunctive across dimensions,
 *    disjunctive within, empty = match-all, job-less events excluded by a jobId filter);
 *  - one end-to-end WebSocket test proving the server actually drops non-matching events
 *    for a `?jobId=` subscription (so the client no longer sifts the firehose itself).
 *
 * Same `EXTERNAL_PG_URL` bypass as the sibling dashboard tests — the WS test needs the Koin
 * graph (and thus a DataSource) the dashboard modules wire up.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class EventSubscriptionFilterTest {

    private val t0: Instant = Instant.fromEpochMilliseconds(0)
    private lateinit var storage: DashboardTestSupport.Storage

    @BeforeAll
    fun setUp() {
        storage = DashboardTestSupport.startStorage()
    }

    @AfterAll
    fun tearDown() {
        DashboardTestSupport.stopKoinSafe()
        storage.shutdown()
    }

    private fun progress(id: String) = WebSocketEvent.JobProgress(id, 0.5f, null, t0)
    private fun stateChanged(id: String, queue: String = "default") =
        WebSocketEvent.JobStateChanged(id, JobState.PROCESSING, JobState.SUCCEEDED, queue, t0)
    private fun created(id: String, queue: String, type: String) =
        WebSocketEvent.JobCreated(id, queue, type, t0)
    private fun workerJoin() = WebSocketEvent.WorkerJoin("node-1", "host-1", t0)

    // ---- EventFilter.matches() algebra -------------------------------------------------

    @Test
    fun `empty filter matches every event`() {
        val f = EventFilter()
        assertTrue(f.isEmpty)
        assertTrue(f.matches(progress("j1")))
        assertTrue(f.matches(workerJoin()))
        assertTrue(f.matches(stateChanged("j2")))
    }

    @Test
    fun `jobId filter keeps the job and drops others and job-less events`() {
        val f = EventFilter(jobIds = setOf("j1"))
        assertTrue(f.matches(progress("j1")))
        assertFalse(f.matches(progress("j2")), "other job excluded")
        assertFalse(f.matches(workerJoin()), "worker_join has no jobId → excluded by a jobId filter")
    }

    @Test
    fun `eventType filter keeps only the listed discriminators`() {
        val f = EventFilter(eventTypes = setOf("job_progress"))
        assertTrue(f.matches(progress("j1")))
        assertFalse(f.matches(stateChanged("j1")), "job_state excluded when only job_progress is requested")
    }

    @Test
    fun `queue filter applies to events that carry a queue`() {
        val f = EventFilter(queues = setOf("email"))
        assertTrue(f.matches(stateChanged("j1", queue = "email")))
        assertTrue(f.matches(created("j2", queue = "email", type = "Send")))
        assertFalse(f.matches(stateChanged("j3", queue = "default")))
        assertFalse(f.matches(workerJoin()), "queue-less event excluded by a queue filter")
    }

    @Test
    fun `dimensions are conjunctive`() {
        val f = EventFilter(jobIds = setOf("j1"), eventTypes = setOf("job_progress"))
        assertTrue(f.matches(progress("j1")))
        assertFalse(f.matches(stateChanged("j1")), "right job, wrong type")
        assertFalse(f.matches(progress("j2")), "right type, wrong job")
    }

    // ---- End-to-end WebSocket filtering ------------------------------------------------

    @Test
    fun `WS subscription with jobId filter receives only that job's events`() = testApplication {
        val target = Uuid.random().toString()
        val other = Uuid.random().toString()

        application {
            install(ServerWebSockets)
            install(ContentNegotiation) { json() }
            install(Koin) {
                modules(*DashboardTestSupport.dashboardModules(storage.dataSource, nodeId = "test-ws-filter"))
            }
            routing { configureDashboardRouting() }
        }
        startApplication()

        val bus: EventBus = DashboardTestSupport.resolve()
        // Decode with the SAME Json the server encodes with (the `_type` polymorphic discriminator).
        val json = DashboardTestSupport.resolve<SchedulerCoreConfig>().json

        val wsClient = createClient { install(ClientWebSockets) }
        wsClient.clientWebSocket("/api/ws/events?jobId=$target") {
            // Publish (other, target) pairs on a loop so we don't race the server-side
            // collector becoming active; within each round ordering is preserved, so if the
            // filter were off the client would see `other` frames interleaved.
            val publisher = launch(Dispatchers.Default) {
                repeat(100) {
                    bus.publish(progress(other))
                    bus.publish(progress(target))
                    delay(20)
                }
            }

            val received = mutableListOf<WebSocketEvent>()
            withTimeoutOrNull(3_000) {
                while (received.size < 5) {
                    val frame = incoming.receive()
                    if (frame is Frame.Text) {
                        received += json.decodeFromString(WebSocketEvent.serializer(), frame.readText())
                    }
                }
            }
            publisher.cancel()

            assertTrue(received.isNotEmpty(), "should receive the target job's events")
            assertTrue(
                received.all { (it as? WebSocketEvent.JobProgress)?.id == target },
                "server must drop jobId=$other events; got ${received.map { (it as? WebSocketEvent.JobProgress)?.id }}",
            )
        }
    }
}
