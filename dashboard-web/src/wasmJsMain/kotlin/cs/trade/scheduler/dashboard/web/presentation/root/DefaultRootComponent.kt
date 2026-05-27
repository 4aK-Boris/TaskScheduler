package cs.trade.scheduler.dashboard.web.presentation.root

import com.arkivanov.decompose.ComponentContext
import com.arkivanov.decompose.router.stack.ChildStack
import com.arkivanov.decompose.router.stack.StackNavigation
import com.arkivanov.decompose.router.stack.childStack
import com.arkivanov.decompose.router.stack.pop
import com.arkivanov.decompose.router.stack.push
import com.arkivanov.decompose.router.stack.replaceCurrent
import com.arkivanov.decompose.value.MutableValue
import com.arkivanov.decompose.value.Value
import com.arkivanov.decompose.value.update
import cs.trade.scheduler.core.frontend.BaseComponent
import cs.trade.scheduler.dashboard.web.data.connection.ConnectionStatus
import cs.trade.scheduler.dashboard.web.data.connection.ConnectionStatusStore
import cs.trade.scheduler.dashboard.web.data.connection.EventStream
import cs.trade.scheduler.dashboard.web.data.persistence.BrowserStorage
import kotlinx.browser.window
import cs.trade.scheduler.dashboard.web.domain.usecases.BulkCancelJobsUseCase
import cs.trade.scheduler.dashboard.web.domain.usecases.BulkDeleteJobsUseCase
import cs.trade.scheduler.dashboard.web.domain.usecases.BulkRetryJobsUseCase
import cs.trade.scheduler.dashboard.web.domain.usecases.CancelJobUseCase
import cs.trade.scheduler.dashboard.web.domain.usecases.DeleteJobUseCase
import cs.trade.scheduler.dashboard.web.domain.usecases.DisableRecurringJobUseCase
import cs.trade.scheduler.dashboard.web.domain.usecases.EnableRecurringJobUseCase
import cs.trade.scheduler.dashboard.web.domain.usecases.GetJobDetailUseCase
import cs.trade.scheduler.dashboard.web.domain.usecases.GetJobsListUseCase
import cs.trade.scheduler.dashboard.web.domain.usecases.GetStatsOverviewUseCase
import cs.trade.scheduler.dashboard.web.domain.usecases.ListKnownTypesUseCase
import cs.trade.scheduler.dashboard.web.domain.usecases.ListPausedTypesUseCase
import cs.trade.scheduler.dashboard.web.domain.usecases.ListRecurringJobsUseCase
import cs.trade.scheduler.dashboard.web.domain.usecases.ListWorkersUseCase
import cs.trade.scheduler.dashboard.web.domain.usecases.PauseTypeUseCase
import cs.trade.scheduler.dashboard.web.domain.usecases.RerouteJobUseCase
import cs.trade.scheduler.dashboard.web.domain.usecases.RetryJobUseCase
import cs.trade.scheduler.dashboard.web.domain.usecases.UnpauseTypeUseCase
import cs.trade.scheduler.dashboard.web.presentation.screens.jobdetail.DefaultJobDetailComponent
import cs.trade.scheduler.dashboard.web.presentation.screens.joblist.DefaultJobListComponent
import cs.trade.scheduler.dashboard.web.presentation.screens.recurring.DefaultRecurringListComponent
import cs.trade.scheduler.dashboard.web.presentation.screens.stats.DefaultStatsComponent
import cs.trade.scheduler.dashboard.web.presentation.screens.types.DefaultTypesComponent
import cs.trade.scheduler.dashboard.web.presentation.screens.workers.DefaultWorkersComponent

// Root nav host. Each child is constructed with its own scoped UseCases — no DI
// container at runtime; the Root passes everything down explicitly.
public class DefaultRootComponent(
    componentContext: ComponentContext,
    private val getJobsList: GetJobsListUseCase,
    private val getJobDetail: GetJobDetailUseCase,
    private val cancelJob: CancelJobUseCase,
    private val retryJob: RetryJobUseCase,
    private val deleteJob: DeleteJobUseCase,
    private val rerouteJob: RerouteJobUseCase,
    private val bulkRetry: BulkRetryJobsUseCase,
    private val bulkCancel: BulkCancelJobsUseCase,
    private val bulkDelete: BulkDeleteJobsUseCase,
    private val listRecurring: ListRecurringJobsUseCase,
    private val enableRecurring: EnableRecurringJobUseCase,
    private val disableRecurring: DisableRecurringJobUseCase,
    private val getStatsOverview: GetStatsOverviewUseCase,
    private val listWorkers: ListWorkersUseCase,
    private val listPausedTypes: ListPausedTypesUseCase,
    private val listKnownTypes: ListKnownTypesUseCase,
    private val pauseType: PauseTypeUseCase,
    private val unpauseType: UnpauseTypeUseCase,
    private val eventStream: EventStream,
    connectionStatus: ConnectionStatusStore,
) : BaseComponent(componentContext), RootComponent {

    private val navigation = StackNavigation<RootComponent.Config>()

    override val stack: Value<ChildStack<RootComponent.Config, RootComponent.Child>> = childStack(
        source = navigation,
        serializer = RootComponent.Config.serializer(),
        initialConfiguration = RootComponent.Config.JobList,
        handleBackButton = true,
        childFactory = ::createChild,
    )

    override val connection: Value<ConnectionStatus> = connectionStatus.state

    // Dark mode: localStorage > OS preference > false. Toggling persists for survive-reload.
    private val _isDarkTheme: MutableValue<Boolean> = MutableValue(initialDarkPreference())
    override val isDarkTheme: Value<Boolean> = _isDarkTheme

    override fun onToggleTheme() {
        _isDarkTheme.update { current ->
            val next = !current
            BrowserStorage.saveBool(DARK_KEY, next)
            next
        }
    }

    override fun onJumpToJob(jobId: String) {
        // Freeform UUID input from the nav search box. Validation happens inside the
        // detail screen — invalid id surfaces as "Job not found".
        val trimmed = jobId.trim()
        if (trimmed.isNotEmpty()) navigation.push(RootComponent.Config.JobDetail(trimmed))
    }

    private fun initialDarkPreference(): Boolean {
        BrowserStorage.load(DARK_KEY)?.toBooleanStrictOrNull()?.let { return it }
        // No persisted choice — match the OS via prefers-color-scheme. Falls back to
        // light if matchMedia isn't available (very old browsers).
        return runCatching {
            window.matchMedia("(prefers-color-scheme: dark)").matches
        }.getOrElse { false }
    }

    override fun onBackClicked() {
        navigation.pop()
    }

    // Top-level nav buttons use replaceCurrent so the stack doesn't grow with each
    // section switch — back from JobList shouldn't walk through Recurring -> Stats.
    override fun onNavigateToJobs() = navigation.replaceCurrent(RootComponent.Config.JobList)
    override fun onNavigateToRecurring() = navigation.replaceCurrent(RootComponent.Config.RecurringList)
    override fun onNavigateToStats() = navigation.replaceCurrent(RootComponent.Config.Stats)
    override fun onNavigateToWorkers() = navigation.replaceCurrent(RootComponent.Config.Workers)
    override fun onNavigateToTypes() = navigation.replaceCurrent(RootComponent.Config.Types)

    private companion object {
        const val DARK_KEY = "dashboard.dark"
    }

    private fun createChild(
        config: RootComponent.Config,
        ctx: ComponentContext,
    ): RootComponent.Child = when (config) {
        RootComponent.Config.JobList -> RootComponent.Child.JobList(
            DefaultJobListComponent(
                componentContext = ctx,
                getJobsList = getJobsList,
                bulkRetry = bulkRetry,
                bulkCancel = bulkCancel,
                bulkDelete = bulkDelete,
                listPausedTypes = listPausedTypes,
                listKnownTypes = listKnownTypes,
                events = eventStream,
                onJobSelected = { jobId -> navigation.push(RootComponent.Config.JobDetail(jobId)) },
            )
        )
        is RootComponent.Config.JobDetail -> RootComponent.Child.JobDetail(
            DefaultJobDetailComponent(
                componentContext = ctx,
                jobId = config.jobId,
                getDetail = getJobDetail,
                cancelJob = cancelJob,
                retryJob = retryJob,
                deleteJob = deleteJob,
                rerouteJob = rerouteJob,
                listPausedTypes = listPausedTypes,
                events = eventStream,
                onBack = { navigation.pop() },
                onNavigateToJob = { id -> navigation.push(RootComponent.Config.JobDetail(id)) },
            )
        )
        RootComponent.Config.RecurringList -> RootComponent.Child.RecurringList(
            DefaultRecurringListComponent(
                componentContext = ctx,
                listUseCase = listRecurring,
                enableUseCase = enableRecurring,
                disableUseCase = disableRecurring,
                events = eventStream,
                onBack = ::onNavigateToJobs,
            )
        )
        RootComponent.Config.Stats -> RootComponent.Child.Stats(
            DefaultStatsComponent(
                componentContext = ctx,
                getOverview = getStatsOverview,
                events = eventStream,
                onBack = ::onNavigateToJobs,
            )
        )
        RootComponent.Config.Workers -> RootComponent.Child.Workers(
            DefaultWorkersComponent(
                componentContext = ctx,
                listUseCase = listWorkers,
                events = eventStream,
                onBack = ::onNavigateToJobs,
            )
        )
        RootComponent.Config.Types -> RootComponent.Child.Types(
            DefaultTypesComponent(
                componentContext = ctx,
                listUseCase = listPausedTypes,
                listKnownUseCase = listKnownTypes,
                pauseUseCase = pauseType,
                unpauseUseCase = unpauseType,
                events = eventStream,
                onBack = ::onNavigateToJobs,
            )
        )
    }
}
