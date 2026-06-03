package cs.trade.scheduler.dashboard.server

import cs.trade.scheduler.dashboard.server.api.descriptions.JobDescription
import cs.trade.scheduler.dashboard.server.api.extractors.JobExtractor
import cs.trade.scheduler.dashboard.server.api.extractors.RecurringExtractor
import cs.trade.scheduler.dashboard.server.api.mappers.JobApiMapper
import cs.trade.scheduler.dashboard.server.api.mappers.RecurringApiMapper
import cs.trade.scheduler.dashboard.server.api.mappers.TypePauseApiMapper
import cs.trade.scheduler.dashboard.server.api.mappers.WorkerApiMapper
import cs.trade.scheduler.dashboard.server.api.validations.JobValidation
import cs.trade.scheduler.dashboard.server.domain.usecases.BulkCancelJobsUseCase
import cs.trade.scheduler.dashboard.server.domain.usecases.BulkDeleteJobsUseCase
import cs.trade.scheduler.dashboard.server.domain.usecases.BulkRetryJobsUseCase
import cs.trade.scheduler.dashboard.server.domain.usecases.CancelJobUseCase
import cs.trade.scheduler.dashboard.server.domain.usecases.DeleteJobUseCase
import cs.trade.scheduler.dashboard.server.domain.usecases.DisableRecurringJobUseCase
import cs.trade.scheduler.dashboard.server.domain.usecases.EnableRecurringJobUseCase
import cs.trade.scheduler.dashboard.server.domain.usecases.GetJobDetailUseCase
import cs.trade.scheduler.dashboard.server.domain.usecases.GetJobsListUseCase
import cs.trade.scheduler.dashboard.server.domain.usecases.GetQueuesHealthUseCase
import cs.trade.scheduler.dashboard.server.domain.usecases.GetStatsOverviewUseCase
import cs.trade.scheduler.dashboard.server.domain.usecases.GetTypesStatsUseCase
import cs.trade.scheduler.dashboard.server.domain.usecases.GetUpcomingUseCase
import cs.trade.scheduler.dashboard.server.domain.usecases.ListJobTypePausesUseCase
import cs.trade.scheduler.dashboard.server.domain.usecases.ListKnownPayloadTypesUseCase
import cs.trade.scheduler.dashboard.server.domain.usecases.ListRecurringJobsUseCase
import cs.trade.scheduler.dashboard.server.domain.usecases.ListWorkersUseCase
import cs.trade.scheduler.dashboard.server.domain.usecases.PauseJobTypeUseCase
import cs.trade.scheduler.dashboard.server.domain.usecases.RerouteJobUseCase
import cs.trade.scheduler.dashboard.server.domain.usecases.RerunJobUseCase
import cs.trade.scheduler.dashboard.server.domain.usecases.RetryJobUseCase
import cs.trade.scheduler.dashboard.server.domain.usecases.TriggerRecurringJobUseCase
import cs.trade.scheduler.dashboard.server.domain.usecases.UnpauseJobTypeUseCase
import io.ktor.server.application.ApplicationCall
import org.koin.core.module.Module
import org.koin.core.module.dsl.singleOf
import org.koin.dsl.module

/**
 * Builder DSL config for the dashboard backend module. See DESIGN.md sections 9 and 12.2.
 *
 * ```
 * schedulerDashboardModule {
 *     port = 8080
 *     auth {
 *         basic {
 *             username = "admin"
 *             password = System.getenv("DASHBOARD_PASSWORD")!!
 *         }
 *     }
 * }
 * ```
 */
public class SchedulerDashboardConfig {
    public var port: Int = 8080

    /**
     * Backpressure indicator thresholds (DESIGN.md 20.10). When a queue's non-terminal
     * job count crosses [QueueHealthThresholds.elevated] it badges yellow; at
     * [QueueHealthThresholds.overloaded] it badges red. Defaults are
     * tuned for the "typical web-app worker pool" — single-digit jobs/sec, queues drain
     * in seconds.
     */
    public var queueHealth: QueueHealthThresholds = QueueHealthThresholds()

    internal var authConfigure: DashboardAuthConfig.() -> Unit = { none() }

    public fun auth(configure: DashboardAuthConfig.() -> Unit) {
        authConfigure = configure
    }
}

/**
 * Thresholds that drive the UI badge color on the backpressure indicator (DESIGN.md
 * 20.10). Validation: must be strictly increasing (elevated < overloaded) and both
 * positive — caught at module instantiation.
 */
public data class QueueHealthThresholds(
    val elevated: Long = 1_000,
    val overloaded: Long = 5_000,
) {
    init {
        require(elevated in 1..overloaded) {
            "elevated ($elevated) must be ≥1 and ≤ overloaded ($overloaded)"
        }
    }
}

public class DashboardAuthConfig {
    internal var kind: AuthKind = AuthKind.None
    internal var basicUser: String? = null
    internal var basicPass: String? = null
    internal var customName: String? = null
    internal var customActorExtractor: ((ApplicationCall) -> String)? = null

    public fun none() { kind = AuthKind.None }

    public fun basic(configure: BasicAuthBuilder.() -> Unit) {
        val b = BasicAuthBuilder().apply(configure)
        kind = AuthKind.Basic
        basicUser = b.username
        basicPass = b.password
    }

    public fun custom(authName: String, configure: CustomAuthBuilder.() -> Unit = {}) {
        kind = AuthKind.Custom
        customName = authName
        customActorExtractor = CustomAuthBuilder().apply(configure).actorExtractor
    }

    internal enum class AuthKind { None, Basic, Custom }
}

public class BasicAuthBuilder {
    public var username: String = "admin"
    public var password: String = ""
}

public class CustomAuthBuilder {
    /** Called per request to derive the `actor` for MANUAL_* job_event rows. */
    public var actorExtractor: ((ApplicationCall) -> String)? = null
}

public fun schedulerDashboardModule(configure: SchedulerDashboardConfig.() -> Unit): Module {
    val config = SchedulerDashboardConfig().apply(configure)
    return module {
        single<SchedulerDashboardConfig> { config }
        // Pull the thresholds out so use cases can constructor-inject a small focused type
        // rather than the whole dashboard config.
        single<QueueHealthThresholds> { config.queueHealth }

        // api/ layer — stateless helpers; one instance per process.
        singleOf(::JobApiMapper)
        singleOf(::JobExtractor)
        singleOf(::JobValidation)
        singleOf(::JobDescription)
        singleOf(::RecurringExtractor)
        singleOf(::RecurringApiMapper)
        singleOf(::WorkerApiMapper)
        singleOf(::TypePauseApiMapper)

        // domain/usecases/ — one per repo method per the architecture rule (DESIGN.md 3.3).
        singleOf(::GetJobsListUseCase)
        singleOf(::GetJobDetailUseCase)
        singleOf(::CancelJobUseCase)
        singleOf(::RetryJobUseCase)
        singleOf(::RerunJobUseCase)
        singleOf(::DeleteJobUseCase)
        singleOf(::RerouteJobUseCase)
        singleOf(::BulkRetryJobsUseCase)
        singleOf(::BulkCancelJobsUseCase)
        singleOf(::BulkDeleteJobsUseCase)
        singleOf(::ListRecurringJobsUseCase)
        singleOf(::EnableRecurringJobUseCase)
        singleOf(::DisableRecurringJobUseCase)
        singleOf(::TriggerRecurringJobUseCase)
        singleOf(::GetUpcomingUseCase)
        singleOf(::GetStatsOverviewUseCase)
        singleOf(::GetTypesStatsUseCase)
        singleOf(::GetQueuesHealthUseCase)
        singleOf(::ListWorkersUseCase)
        singleOf(::ListJobTypePausesUseCase)
        singleOf(::ListKnownPayloadTypesUseCase)
        singleOf(::PauseJobTypeUseCase)
        singleOf(::UnpauseJobTypeUseCase)
    }
}
