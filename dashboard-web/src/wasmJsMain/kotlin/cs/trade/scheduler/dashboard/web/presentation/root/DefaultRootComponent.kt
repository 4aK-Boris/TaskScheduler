package cs.trade.scheduler.dashboard.web.presentation.root

import com.arkivanov.decompose.ComponentContext
import com.arkivanov.decompose.DelicateDecomposeApi
import com.arkivanov.decompose.ExperimentalDecomposeApi
import com.arkivanov.decompose.router.stack.ChildStack
import com.arkivanov.decompose.router.stack.StackNavigation
import com.arkivanov.decompose.router.stack.childStack
import com.arkivanov.decompose.router.stack.pop
import com.arkivanov.decompose.router.stack.push
import com.arkivanov.decompose.router.stack.replaceCurrent
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
import cs.trade.scheduler.dashboard.web.domain.usecases.ListKnownTypesUseCase
import cs.trade.scheduler.dashboard.web.domain.usecases.ListPausedTypesUseCase
import cs.trade.scheduler.dashboard.web.domain.usecases.ListQueuesHealthUseCase
import cs.trade.scheduler.dashboard.web.domain.usecases.ListRecurringJobsUseCase
import cs.trade.scheduler.dashboard.web.domain.usecases.ListTypeStatsUseCase
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
import cs.trade.scheduler.dashboard.web.presentation.screens.typesstats.DefaultTypeStatsComponent
import cs.trade.scheduler.dashboard.web.presentation.screens.workers.DefaultWorkersComponent

// Root nav host. Each child is constructed with its own scoped UseCases — no DI
// container at runtime; the Root passes everything down explicitly.
// push/replaceCurrent are @DelicateDecomposeApi (pushing a duplicate config is the caller's
// responsibility) — we accept that here intentionally; pushNew would throw on a repeat JobDetail.
@OptIn(ExperimentalDecomposeApi::class, DelicateDecomposeApi::class)
public class DefaultRootComponent(
    componentContext: ComponentContext,
    // Browser query string at startup (window.location.search, e.g. "?jobs/{id}") — restores the
    // screen on reload / shared link. Null in non-web hosts (none today, but keeps it host-agnostic).
    private val deepLinkPath: String?,
    // Mirrors the ChildStack into the browser address bar + Back/Forward. Null disables web history.
    private val webHistoryController: WebHistoryController?,
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
    private val listQueuesHealth: ListQueuesHealthUseCase,
    private val pauseType: PauseTypeUseCase,
    private val unpauseType: UnpauseTypeUseCase,
    private val listTypeStats: ListTypeStatsUseCase,
    private val eventStream: EventStream,
    connectionStatus: ConnectionStatusStore,
) : BaseComponent(componentContext), RootComponent {

    private val navigation = StackNavigation<RootComponent.Config>()

    override val stack: Value<ChildStack<RootComponent.Config, RootComponent.Child>> = childStack(
        source = navigation,
        serializer = RootComponent.Config.serializer(),
        initialStack = ::buildInitialStack,
        // Browser Back/Forward is driven by the WebHistoryController (init block below), not the
        // system BackHandler — on web that handler doesn't see the browser's own back button.
        handleBackButton = false,
        childFactory = ::createChild,
    )

    init {
        // Mirror the ChildStack into the browser address bar + Back/Forward.
        webHistoryController?.attach(
            navigator = navigation,
            stack = stack,
            serializer = RootComponent.Config.serializer(),
            getPath = ::pathForConfig,
            getConfiguration = ::configForPath,
        )
    }

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
    override fun onNavigateToTypeStats() = navigation.replaceCurrent(RootComponent.Config.TypeStats)

    // ---- web history (URL <-> ChildStack) ---------------------------------------------------

    private fun buildInitialStack(): List<RootComponent.Config> {
        // After a reload Decompose can rebuild the whole stack from the paths it stashed in
        // history.state; use them when present.
        val paths = webHistoryController?.historyPaths?.takeUnless { it.isEmpty() }
        if (paths != null) return paths.map(::configForPath)
        // Fresh load or a pasted/shared link: expand a "?jobs/{id}" deep link to [list, detail] so
        // Back returns to the list instead of exiting the app.
        val config = configForPath(deepLinkPath ?: "")
        return if (config is RootComponent.Config.JobDetail) {
            listOf(RootComponent.Config.JobList, config)
        } else {
            listOf(config)
        }
    }

    // Query-string routing, NOT clean paths: the URL keeps the path at "/" and puts the screen in
    // the query (e.g. "/?jobs/{id}"). For a wasm SPA this is far more robust — the server always
    // sees "/", so reload / shared links / assets all work with zero server or webpack config,
    // unlike "/jobs/{id}" paths which would 404 / fail to load assets without a SPA fallback.
    private fun pathForConfig(config: RootComponent.Config): String = when (config) {
        RootComponent.Config.JobList -> "?jobs"
        is RootComponent.Config.JobDetail -> "?jobs/${config.jobId}"
        RootComponent.Config.RecurringList -> "?recurring"
        RootComponent.Config.Workers -> "?workers"
        RootComponent.Config.Types -> "?types"
        RootComponent.Config.TypeStats -> "?type-stats"
        RootComponent.Config.Stats -> "?stats"
    }

    private fun configForPath(path: String): RootComponent.Config {
        // Accepts both the stored "?jobs/{id}" and a bare "jobs/{id}"; the leading "?" is the query.
        val parts = path.removePrefix("?").trim('/').split('/').filter { it.isNotEmpty() }
        return when (parts.firstOrNull()) {
            // empty (root) and "jobs" → list; "jobs/{id}" → that job's detail.
            null, "jobs" -> parts.getOrNull(1)?.let { RootComponent.Config.JobDetail(it) }
                ?: RootComponent.Config.JobList
            "recurring" -> RootComponent.Config.RecurringList
            "workers" -> RootComponent.Config.Workers
            "types" -> RootComponent.Config.Types
            "type-stats" -> RootComponent.Config.TypeStats
            "stats" -> RootComponent.Config.Stats
            else -> RootComponent.Config.JobList
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
        RootComponent.Config.TypeStats -> RootComponent.Child.TypeStats(
            DefaultTypeStatsComponent(
                componentContext = ctx,
                listUseCase = listTypeStats,
                onBack = ::onNavigateToJobs,
            )
        )
    }
}
