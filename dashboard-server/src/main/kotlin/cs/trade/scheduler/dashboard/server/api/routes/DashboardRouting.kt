package cs.trade.scheduler.dashboard.server.api.routes

import io.ktor.server.routing.Route

/**
 * Top-level routing aggregator. Single source registered in [Application.configureRouting]
 * in :standalone-runner.
 *
 * Receiver is `Route` (not `Routing`) so that wrapping the call in `authenticate("dashboard") { … }`
 * actually nests these routes under the auth interceptor — a `Routing.()` receiver would
 * escape the lambda's `Route` scope and re-mount at the root, silently bypassing auth.
 */
public fun Route.configureDashboardRouting() {
    configureJobsRouting()
    configureEventsRouting()
    configureRecurringRouting()
    configureStatsRouting()
    configureQueuesRouting()
    configureWorkersRouting()
    configureTypesRouting()
}
