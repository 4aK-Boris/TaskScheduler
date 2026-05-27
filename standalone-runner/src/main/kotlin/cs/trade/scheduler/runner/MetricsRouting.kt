package cs.trade.scheduler.runner

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respondText
import io.ktor.server.routing.Routing
import io.ktor.server.routing.get
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry

// /metrics — Prometheus text exposition. Mounted outside the dashboard auth block so
// scrapers (Prometheus, kube-prometheus-stack ServiceMonitor) don't need credentials.
// Restrict at the network layer if the registry exposes sensitive labels.
public fun Routing.configureMetricsRouting(registry: PrometheusMeterRegistry) {
    get("/metrics") {
        call.respondText(
            text = registry.scrape(),
            // Prometheus exposition format is plain text but expects this exact MIME.
            contentType = ContentType.parse("text/plain; version=0.0.4; charset=utf-8"),
            status = HttpStatusCode.OK,
        )
    }
}
