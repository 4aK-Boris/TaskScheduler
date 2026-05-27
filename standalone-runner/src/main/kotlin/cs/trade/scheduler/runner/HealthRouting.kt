package cs.trade.scheduler.runner

import com.rabbitmq.client.ConnectionFactory
import cs.trade.scheduler.engine.infra.infrastructure.leader.LeaderElection
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respondText
import io.ktor.server.routing.Routing
import io.ktor.server.routing.get
import io.ktor.server.routing.route
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import javax.sql.DataSource
import kotlin.time.Duration.Companion.seconds

// /health/live   — always 200; "the JVM is up" liveness probe.
// /health/ready  — checks DB (isValid, 1s) and Rabbit (handshake, 2s hard cap);
//                   503 on any failure with a tiny per-check JSON body.
// /health/leader — reports whether this replica currently holds the PG advisory lock.
//                   Useful for routing operator traffic and for "who's leader?" debug.
//
// Mounted OUTSIDE the dashboard auth block — k8s liveness/readiness probes don't carry
// BasicAuth and shouldn't have to.
public fun Routing.configureHealthRouting(
    dataSource: DataSource,
    rabbitFactory: ConnectionFactory,
    leader: LeaderElection,
) {
    route("/health") {
        get("/live") {
            call.respondText("ok", contentType = ContentType.Text.Plain, status = HttpStatusCode.OK)
        }

        get("/ready") {
            val dbOk = pingDatabase(dataSource)
            val rabbitOk = pingRabbit(rabbitFactory)
            val ready = dbOk && rabbitOk
            val body = """{"db":${dbOk},"rabbit":${rabbitOk}}"""
            call.respondText(
                text = body,
                contentType = ContentType.Application.Json,
                status = if (ready) HttpStatusCode.OK else HttpStatusCode.ServiceUnavailable,
            )
        }

        get("/leader") {
            val isLeader = leader.isCurrentLeader()
            // Always 200 — both leader and follower are healthy states. The body tells
            // the caller which role we play right now; k8s probes don't care, ops tools do.
            call.respondText(
                text = """{"leader":${isLeader}}""",
                contentType = ContentType.Application.Json,
                status = HttpStatusCode.OK,
            )
        }
    }
}

private suspend fun pingDatabase(dataSource: DataSource): Boolean = runCatching {
    // Hop to IO — Hikari's getConnection can block when the pool is saturated, and we
    // don't want a sluggish DB to wedge the Netty event loop running this handler.
    withContext(Dispatchers.IO) {
        dataSource.connection.use { conn ->
            conn.isValid(/* timeoutSeconds = */ 1)
        }
    }
}.getOrDefault(false)

private suspend fun pingRabbit(factory: ConnectionFactory): Boolean = try {
    // factory.newConnection opens a TCP socket + does the AMQP handshake — both can hang
    // indefinitely if the broker is wedged (DNS, half-open socket, queue under heavy
    // load). withTimeout is the only way to bound it: ConnectionFactory.connectionTimeout
    // covers TCP only, not the handshake. 2s matches the readiness probe SLA.
    //
    // Explicit try/catch (not runCatching) so a stray TimeoutCancellationException
    // from withTimeout is treated as a probe failure rather than escaping as cancellation.
    withTimeout(2.seconds) {
        withContext(Dispatchers.IO) {
            factory.newConnection("health-check").use { conn -> conn.isOpen }
        }
    }
} catch (_: TimeoutCancellationException) {
    false
} catch (_: Throwable) {
    false
}
