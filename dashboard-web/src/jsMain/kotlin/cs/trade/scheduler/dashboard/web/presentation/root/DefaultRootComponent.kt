package cs.trade.scheduler.dashboard.web.presentation.root

import com.arkivanov.decompose.ComponentContext
import com.arkivanov.decompose.ExperimentalDecomposeApi
import com.arkivanov.decompose.router.stack.ChildStack
import com.arkivanov.decompose.router.stack.StackNavigation
import com.arkivanov.decompose.router.stack.childStack
import com.arkivanov.decompose.router.stack.pop
import com.arkivanov.decompose.router.stack.pushToFront
import com.arkivanov.decompose.router.stack.replaceAll
import com.arkivanov.decompose.router.stack.webhistory.WebHistoryController
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
import cs.trade.scheduler.dashboard.web.domain.usecases.GetUpcomingUseCase
import cs.trade.scheduler.dashboard.web.domain.usecases.ListKnownTypesUseCase
import cs.trade.scheduler.dashboard.web.domain.usecases.ListPausedTypesUseCase
import cs.trade.scheduler.dashboard.web.domain.usecases.ListQueuesHealthUseCase
import cs.trade.scheduler.dashboard.web.domain.usecases.ListRecurringJobsUseCase
import cs.trade.scheduler.dashboard.web.domain.usecases.ListTypeStatsUseCase
import cs.trade.scheduler.dashboard.web.domain.usecases.ListWorkersUseCase
import cs.trade.scheduler.dashboard.web.domain.usecases.PauseTypeUseCase
import cs.trade.scheduler.dashboard.web.domain.usecases.RerouteJobUseCase
import cs.trade.scheduler.dashboard.web.domain.usecases.RerunJobUseCase
import cs.trade.scheduler.dashboard.web.domain.usecases.RetryJobUseCase
import cs.trade.scheduler.dashboard.web.domain.usecases.TriggerRecurringJobUseCase
import cs.trade.scheduler.dashboard.web.domain.usecases.UnpauseTypeUseCase
import cs.trade.scheduler.dashboard.web.presentation.screens.jobdetail.DefaultJobDetailComponent
import cs.trade.scheduler.dashboard.web.presentation.screens.joblist.DefaultJobListComponent
import cs.trade.scheduler.dashboard.web.presentation.screens.recurring.DefaultRecurringListComponent
import cs.trade.scheduler.dashboard.web.presentation.screens.stats.DefaultStatsComponent
import cs.trade.scheduler.dashboard.web.presentation.screens.types.DefaultTypesComponent
import cs.trade.scheduler.dashboard.web.presentation.screens.typesstats.DefaultTypeStatsComponent
import cs.trade.scheduler.dashboard.web.presentation.screens.upcoming.DefaultUpcomingComponent
import cs.trade.scheduler.dashboard.web.presentation.screens.workers.DefaultWorkersComponent

// Root nav host. Each child is constructed with its own scoped UseCases — no DI
// container at runtime; the Root passes everything down explicitly.
// WebHistoryController is @ExperimentalDecomposeApi (browser address-bar sync — wasm only).
@OptIn(ExperimentalDecomposeApi::class)
public class DefaultRootComponent(
    componentContext: ComponentContext,
    private val getJobsList: GetJobsListUseCase,
    private val getJobDetail: GetJobDetailUseCase,
    private val cancelJob: CancelJobUseCase,
    private val retryJob: RetryJobUseCase,
    private val deleteJob: DeleteJobUseCase,
    private val rerouteJob: RerouteJobUseCase,
    private val rerunJob: RerunJobUseCase,
    private val bulkRetry: BulkRetryJobsUseCase,
    private val bulkCancel: BulkCancelJobsUseCase,
    private val bulkDelete: BulkDeleteJobsUseCase,
    private val listRecurring: ListRecurringJobsUseCase,
    private val enableRecurring: EnableRecurringJobUseCase,
    private val disableRecurring: DisableRecurringJobUseCase,
    private val triggerRecurring: TriggerRecurringJobUseCase,
    private val getStatsOverview: GetStatsOverviewUseCase,
    private val getUpcoming: GetUpcomingUseCase,
    private val listWorkers: ListWorkersUseCase,
    private val listPausedTypes: ListPausedTypesUseCase,
    private val listKnownTypes: ListKnownTypesUseCase,
    private val listQueuesHealth: ListQueuesHealthUseCase,
    private val pauseType: PauseTypeUseCase,
    private val unpauseType: UnpauseTypeUseCase,
    private val listTypeStats: ListTypeStatsUseCase,
    private val eventStream: EventStream,
    connectionStatus: ConnectionStatusStore,
    // Web-history integration (wasm only): mirrors the stack into the address bar and lets the
    // browser Back/Forward buttons drive navigation. Null in tests / non-web hosts.
    private val webHistoryController: WebHistoryController? = null,
    // window.location.pathname at startup — used to deep-link the initial stack on first load
    // (and on a full reload), so /jobs/{id} reopens the detail with the list underneath it.
    deepLinkPath: String? = null,
) : BaseComponent(componentContext), RootComponent {

    private val navigation = StackNavigation<RootComponent.Config>()

    override val stack: Value<ChildStack<RootComponent.Config, RootComponent.Child>> = childStack(
        source = navigation,
        serializer = RootComponent.Config.serializer(),
        initialStack = { buildInitialStack(deepLinkPath) },
        handleBackButton = true,
        childFactory = ::createChild,
    )

    override val connection: Value<ConnectionStatus> = connectionStatus.state

    // Dark mode: localStorage > OS preference > false. Toggling persists for survive-reload.
    private val _isDarkTheme: MutableValue<Boolean> = MutableValue(initialDarkPreference())
    override val isDarkTheme: Value<Boolean> = _isDarkTheme

    init {
        // Sync the stack with browser history. The controller diffs the stack on every change
        // and pushes/pops/replaces address-bar entries to match, and drives the navigator when
        // the user hits Back/Forward. No-op when no controller was supplied (tests).
        webHistoryController?.attach(
            navigator = navigation,
            stack = stack,
            serializer = RootComponent.Config.serializer(),
            getPath = ::getPathForConfig,
            getConfiguration = ::getConfigForPath,
        )
    }

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
        if (trimmed.isNotEmpty()) openJob(trimmed)
    }

    // The ONLY way to open a job detail. pushToFront, never push: a plain push of a jobId that is
    // already somewhere in the stack is a duplicate configuration, and Decompose does not merely
    // reject that navigation — the exception poisons the navigation Relay permanently ("Can't
    // process the event due to a previous failure"), so every later click (other jobs, the section
    // nav, Back) silently does nothing until a full page reload. Walking a dependency chain hits
    // this constantly: A → B, then B's graph offers A again. pushToFront moves an already-open job
    // to the top instead of duplicating it, so the stack stays unique by construction.
    private fun openJob(jobId: String) {
        navigation.pushToFront(RootComponent.Config.JobDetail(jobId))
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

    // Top-level sections reset the whole stack to a single screen via replaceAll. NOT replaceCurrent:
    // that replaces only the top item, so from a job detail ([JobList, JobDetail]) switching back to
    // Jobs would build [JobList, JobList] — a duplicate configuration, which Decompose rejects with an
    // exception, wedging all further navigation until reload. replaceAll keeps each section depth-1.
    override fun onNavigateToJobs() = navigation.replaceAll(RootComponent.Config.JobList)
    override fun onNavigateToRecurring() = navigation.replaceAll(RootComponent.Config.RecurringList)
    override fun onNavigateToUpcoming() = navigation.replaceAll(RootComponent.Config.Upcoming)
    override fun onNavigateToStats() = navigation.replaceAll(RootComponent.Config.Stats)
    override fun onNavigateToWorkers() = navigation.replaceAll(RootComponent.Config.Workers)
    override fun onNavigateToTypes() = navigation.replaceAll(RootComponent.Config.Types)
    override fun onNavigateToTypeStats() = navigation.replaceAll(RootComponent.Config.TypeStats)

    // ---- web-history mapping (config <-> URL path) ---------------------------------------------
    // JobList is the root "/" so a fresh load doesn't rewrite the bar to "/jobs". A JobDetail is a
    // child of the list ("/jobs/{id}"), so a deep-linked reload restores [JobList, JobDetail] and
    // Back returns to the list rather than dead-ending.

    private fun getPathForConfig(config: RootComponent.Config): String = when (config) {
        RootComponent.Config.JobList -> "/"
        is RootComponent.Config.JobDetail -> "/jobs/${config.jobId}"
        RootComponent.Config.RecurringList -> "/recurring"
        RootComponent.Config.Upcoming -> "/upcoming"
        RootComponent.Config.Stats -> "/stats"
        RootComponent.Config.Workers -> "/workers"
        RootComponent.Config.Types -> "/types"
        RootComponent.Config.TypeStats -> "/type-stats"
    }

    private fun getConfigForPath(path: String): RootComponent.Config {
        val segments = path.trim('/').split('/').filter { it.isNotEmpty() }
        return when (segments.firstOrNull()) {
            // "/jobs" → list, "/jobs/{id}" → that job's detail.
            "jobs" -> segments.getOrNull(1)
                ?.let { RootComponent.Config.JobDetail(it) }
                ?: RootComponent.Config.JobList
            "recurring" -> RootComponent.Config.RecurringList
            "upcoming" -> RootComponent.Config.Upcoming
            "stats" -> RootComponent.Config.Stats
            "workers" -> RootComponent.Config.Workers
            "types" -> RootComponent.Config.Types
            "type-stats" -> RootComponent.Config.TypeStats
            // Root "/" and anything unrecognised land on the job list.
            else -> RootComponent.Config.JobList
        }
    }

    private fun buildInitialStack(deepLinkPath: String?): List<RootComponent.Config> {
        val target = deepLinkPath?.let(::getConfigForPath) ?: RootComponent.Config.JobList
        // A job detail sits on top of the list so Back has somewhere to go after a deep link.
        return if (target is RootComponent.Config.JobDetail) {
            listOf(RootComponent.Config.JobList, target)
        } else {
            listOf(target)
        }
    }

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
                listQueuesHealth = listQueuesHealth,
                events = eventStream,
                onJobSelected = ::openJob,
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
                rerunJob = rerunJob,
                listPausedTypes = listPausedTypes,
                events = eventStream,
                onBack = { navigation.pop() },
                onNavigateToJob = ::openJob,
            )
        )
        RootComponent.Config.RecurringList -> RootComponent.Child.RecurringList(
            DefaultRecurringListComponent(
                componentContext = ctx,
                listUseCase = listRecurring,
                enableUseCase = enableRecurring,
                disableUseCase = disableRecurring,
                triggerUseCase = triggerRecurring,
                events = eventStream,
                onBack = ::onNavigateToJobs,
                onNavigateToJob = ::openJob,
            )
        )
        RootComponent.Config.Upcoming -> RootComponent.Child.Upcoming(
            DefaultUpcomingComponent(
                componentContext = ctx,
                getUpcoming = getUpcoming,
                events = eventStream,
                onBack = ::onNavigateToJobs,
                onJobSelected = ::openJob,
                onRecurring = ::onNavigateToRecurring,
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
        RootComponent.Config.TypeStats -> RootComponent.Child.TypeStats(
            DefaultTypeStatsComponent(
                componentContext = ctx,
                listUseCase = listTypeStats,
                onBack = ::onNavigateToJobs,
            )
        )
    }
}
