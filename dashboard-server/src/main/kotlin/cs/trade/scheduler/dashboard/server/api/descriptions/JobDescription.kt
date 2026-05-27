package cs.trade.scheduler.dashboard.server.api.descriptions

import cs.trade.scheduler.core.backend.ktor.BaseDescription
import org.koin.core.annotation.Single

/**
 * Swagger/OpenAPI descriptions for /api/jobs routes. Placeholder — full description DSL
 * (mirroring main project's `BaseDescription` pattern with `RouteConfig` blocks) lands
 * when OpenAPI plugin is selected.
 */
@Single
public class JobDescription : BaseDescription() {
    public companion object {
        public const val JOB_ID_PARAMETER_NAME: String = "jobId"
    }
}
