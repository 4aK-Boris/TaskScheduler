package cs.trade.scheduler.core.backend.ktor

/**
 * Marker base for per-module Swagger/OpenAPI descriptions. Concrete descriptions live in
 * `<module>.api.descriptions/`, see DESIGN.md section 3.3.
 *
 * Subclasses declare `fun {action}{Resource}Description(routeConfig: RouteConfig)` methods.
 *
 * MVP note: OpenAPI integration in Ktor 3.x is in flux — this is a placeholder class without
 * the full Swagger plumbing yet. To be filled in when wiring `:dashboard-server` routes.
 */
public abstract class BaseDescription
