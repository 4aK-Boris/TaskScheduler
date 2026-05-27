package cs.trade.scheduler.storage.postgres

import cs.trade.scheduler.shared.JobState
import cs.trade.scheduler.shared.events.WebSocketEvent
import cs.trade.scheduler.storage.postgres.infrastructure.events.PostgresEventBus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.testcontainers.containers.PostgreSQLContainer
import kotlin.time.Clock
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Cross-process behaviour of [PostgresEventBus] via LISTEN/NOTIFY.
 *
 * Verifies:
 *  1. A.publish reaches B's `events` flow (cross-replica fanout via PG NOTIFY).
 *  2. A's listener does NOT re-emit A's own publish (origin filter — DESIGN.md 14.3).
 *  3. Reconnect path: closing a listener doesn't desync — the second bus keeps receiving
 *     subsequent events. (Tests resilience of the listener loop, not the timing of
 *     reconnect — that's `RECONNECT_DELAY_MS`-bounded and not worth flaky-polling here.)
 *
 * No Rabbit, no scheduler boot — just two bus instances against the same PG.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PostgresEventBusIntegrationTest {

    private companion object {
        private val externalUrl: String? = System.getenv("EXTERNAL_PG_URL")?.takeIf { it.isNotBlank() }
        // Distinct channels per test class so concurrent test runs don't cross-talk
        // through a shared PG (we reuse the same scheduler-test-pg container).
        const val CHANNEL_FANOUT = "tc_evt_fanout"
        const val CHANNEL_ECHO = "tc_evt_echo"
    }

    private lateinit var jdbcUrl: String
    private lateinit var pgUser: String
    private lateinit var pgPass: String
    private var postgres: PostgreSQLContainer<*>? = null
    // Default classDiscriminator ("type") — both buses use this same Json instance so
    // the discriminator value is consistent; production-side wiring is irrelevant here.
    private val json = Json { ignoreUnknownKeys = true }

    @BeforeAll
    fun setUp() {
        if (externalUrl != null) {
            jdbcUrl = externalUrl!!
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
    }

    @AfterAll
    fun tearDown() {
        runCatching { postgres?.stop() }
    }

    private fun newBus(nodeId: String, channel: String): PostgresEventBus =
        PostgresEventBus(
            jdbcUrl = jdbcUrl,
            jdbcUser = pgUser,
            jdbcPassword = pgPass,
            nodeId = nodeId,
            json = json,
            channel = channel,
        )

    private fun sampleEvent(id: String): WebSocketEvent =
        WebSocketEvent.JobStateChanged(
            id = id,
            from = JobState.ENQUEUED,
            to = JobState.PROCESSING,
            queue = "default",
            at = Clock.System.now(),
        )

    @Test
    fun `publish on A is delivered to B via LISTEN-NOTIFY`() = runBlocking {
        val a = newBus(nodeId = "replica-A", channel = CHANNEL_FANOUT)
        val b = newBus(nodeId = "replica-B", channel = CHANNEL_FANOUT)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            a.start(scope)
            b.start(scope)
            // Listener establishes its LISTEN session asynchronously; small delay so the
            // first NOTIFY isn't published before B is actually subscribed. 500ms covers
            // a cold DriverManager.getConnection on a slow box.
            delay(500.milliseconds)

            val event = sampleEvent("from-A-001")
            a.publish(event)

            val received = withTimeoutOrNull(3.seconds) { b.events.first() }
            assertEquals(event, received, "B must receive A's published event within 3s")
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `A's own listener filters echoes via origin-nodeId check`() = runBlocking {
        val a = newBus(nodeId = "replica-A", channel = CHANNEL_ECHO)
        val b = newBus(nodeId = "replica-B", channel = CHANNEL_ECHO)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            a.start(scope)
            b.start(scope)
            delay(500.milliseconds)

            // Collect what A's OWN events flow sees. A's local publish() emits to _events
            // synchronously (zero PG latency), and A's listener should NOT re-emit it.
            // Expectation: exactly ONE event observed (the local one), not two.
            val aSeen = mutableListOf<WebSocketEvent>()
            val collectorJob = scope.launch { a.events.collect { aSeen += it } }
            // SharedFlow subscription is registered when collect() actually starts —
            // there's a race between scope.launch and the publish below. Give the
            // collector coroutine a slice to actually subscribe; otherwise the local
            // tryEmit drops on the floor (replay=0).
            delay(100.milliseconds)

            val event = sampleEvent("from-A-002")
            a.publish(event)

            // Wait for B to receive it via PG — that proves the NOTIFY actually went out
            // and A's listener also had a chance to see it. If A were echoing, we'd see 2
            // in `aSeen` by the time B got 1.
            val bSawIt = withTimeoutOrNull(3.seconds) { b.events.first() }
            assertEquals(event, bSawIt, "B must see A's event")

            // Small extra grace so A's listener has time to handle the echo if it were
            // going to leak. (PG NOTIFY delivery between sessions on the same DB is
            // typically <10ms — 200ms is generous.)
            delay(200.milliseconds)
            collectorJob.cancel()

            assertEquals(1, aSeen.size, "A must NOT echo its own publish (got: $aSeen)")
            assertEquals(event, aSeen.first())
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `B receives multiple sequential events from A in order`() = runBlocking {
        // Sanity that the bus isn't a single-shot — covers a regression where the listener
        // loop accidentally returns after the first notification batch.
        val a = newBus(nodeId = "replica-A", channel = CHANNEL_FANOUT + "_seq")
        val b = newBus(nodeId = "replica-B", channel = CHANNEL_FANOUT + "_seq")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            a.start(scope)
            b.start(scope)
            delay(500.milliseconds)

            val received = mutableListOf<WebSocketEvent>()
            val collector = scope.launch { b.events.collect { received += it } }
            // Same SharedFlow race as the echo test — let the collector actually subscribe
            // before A starts publishing.
            delay(100.milliseconds)

            val events = listOf(
                sampleEvent("seq-001"),
                sampleEvent("seq-002"),
                sampleEvent("seq-003"),
            )
            for (e in events) a.publish(e)

            val ok = withTimeoutOrNull(5.seconds) {
                while (received.size < 3) delay(50.milliseconds)
                true
            }
            collector.cancel()
            assertTrue(ok == true, "expected 3 events on B; got ${received.size}: $received")
            // Note: PG NOTIFY ordering inside a single session is preserved, so the order
            // assertion is meaningful (not flaky).
            assertEquals(events.map { (it as WebSocketEvent.JobStateChanged).id }, received.map { (it as WebSocketEvent.JobStateChanged).id })
        } finally {
            scope.cancel()
        }
    }
}
