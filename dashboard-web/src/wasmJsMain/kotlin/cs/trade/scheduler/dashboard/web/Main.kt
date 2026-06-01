package cs.trade.scheduler.dashboard.web

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.window.ComposeViewport
import com.arkivanov.decompose.DefaultComponentContext
import com.arkivanov.decompose.ExperimentalDecomposeApi
import com.arkivanov.decompose.router.stack.webhistory.DefaultWebHistoryController
import com.arkivanov.essenty.lifecycle.LifecycleRegistry
import cs.trade.scheduler.dashboard.web.data.connection.ConnectionStatusStore
import cs.trade.scheduler.dashboard.web.data.connection.EventStream
import cs.trade.scheduler.dashboard.web.data.repositories.JobsRepositoryImpl
import cs.trade.scheduler.dashboard.web.data.repositories.QueueHealthRepositoryImpl
import cs.trade.scheduler.dashboard.web.data.repositories.RecurringRepositoryImpl
import cs.trade.scheduler.dashboard.web.data.repositories.StatsRepositoryImpl
import cs.trade.scheduler.dashboard.web.data.repositories.UpcomingRepositoryImpl
import cs.trade.scheduler.dashboard.web.data.repositories.TypeStatsRepositoryImpl
import cs.trade.scheduler.dashboard.web.data.repositories.TypesRepositoryImpl
import cs.trade.scheduler.dashboard.web.data.repositories.WorkersRepositoryImpl
import cs.trade.scheduler.dashboard.web.data.mock.MockJobsRepository
import cs.trade.scheduler.dashboard.web.data.mock.MockQueueHealthRepository
import cs.trade.scheduler.dashboard.web.data.mock.MockRecurringRepository
import cs.trade.scheduler.dashboard.web.data.mock.MockStatsRepository
import cs.trade.scheduler.dashboard.web.data.mock.MockUpcomingRepository
import cs.trade.scheduler.dashboard.web.data.mock.MockTypeStatsRepository
import cs.trade.scheduler.dashboard.web.data.mock.MockTypesRepository
import cs.trade.scheduler.dashboard.web.data.mock.MockWorkersRepository
import cs.trade.scheduler.dashboard.web.domain.repositories.JobsRepository
import cs.trade.scheduler.dashboard.web.domain.repositories.QueueHealthRepository
import cs.trade.scheduler.dashboard.web.domain.repositories.RecurringRepository
import cs.trade.scheduler.dashboard.web.domain.repositories.StatsRepository
import cs.trade.scheduler.dashboard.web.domain.repositories.UpcomingRepository
import cs.trade.scheduler.dashboard.web.domain.repositories.TypeStatsRepository
import cs.trade.scheduler.dashboard.web.domain.repositories.TypesRepository
import cs.trade.scheduler.dashboard.web.domain.repositories.WorkersRepository
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
import cs.trade.scheduler.dashboard.web.presentation.root.DefaultRootComponent
import cs.trade.scheduler.dashboard.web.presentation.root.RootContent
import kotlinx.browser.document
import kotlinx.browser.window
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

// Wasm entry point. Composition root: hand-wires repositories and UseCases, then hands
// them to DefaultRootComponent. No DI container in the browser bundle to keep the wasm
// output small and the wiring obvious.
@OptIn(ExperimentalComposeUiApi::class, ExperimentalDecomposeApi::class)
fun main() {
    val lifecycle = LifecycleRegistry()

    // `?mock` swaps the REST-backed repos for in-memory sample data so the dashboard renders
    // populated screens with no backend (local UI work). Production URLs never carry it.
    val mock = window.location.search.contains("mock")
    val jobsRepository: JobsRepository = if (mock) MockJobsRepository() else JobsRepositoryImpl()
    val recurringRepository: RecurringRepository = if (mock) MockRecurringRepository() else RecurringRepositoryImpl()
    val statsRepository: StatsRepository = if (mock) MockStatsRepository() else StatsRepositoryImpl()
    val workersRepository: WorkersRepository = if (mock) MockWorkersRepository() else WorkersRepositoryImpl()
    val typesRepository: TypesRepository = if (mock) MockTypesRepository() else TypesRepositoryImpl()
    val typeStatsRepository: TypeStatsRepository = if (mock) MockTypeStatsRepository() else TypeStatsRepositoryImpl()
    val queueHealthRepository: QueueHealthRepository = if (mock) MockQueueHealthRepository() else QueueHealthRepositoryImpl()
    val upcomingRepository: UpcomingRepository = if (mock) MockUpcomingRepository() else UpcomingRepositoryImpl()

    // Shared WS subscription for the tab's lifetime — owns the reconnect loop and
    // feeds the connection-status badge. SupervisorJob so a hiccup inside the loop
    // can never kill the rest of the app.
    val connectionStatus = ConnectionStatusStore()
    val eventStream = EventStream(connectionStatus)
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    eventStream.start(appScope)

    // Browser address-bar sync + Back/Forward. The deep link is the path the page loaded at
    // (e.g. "/jobs/{id}" after a reload or a shared link); the root uses it for the initial stack.
    val webHistory = DefaultWebHistoryController()
    val deepLink = window.location.pathname

    val root = DefaultRootComponent(
        componentContext = DefaultComponentContext(lifecycle = lifecycle),
        getJobsList = GetJobsListUseCase(jobsRepository),
        getJobDetail = GetJobDetailUseCase(jobsRepository),
        cancelJob = CancelJobUseCase(jobsRepository),
        retryJob = RetryJobUseCase(jobsRepository),
        deleteJob = DeleteJobUseCase(jobsRepository),
        rerouteJob = RerouteJobUseCase(jobsRepository),
        rerunJob = RerunJobUseCase(jobsRepository),
        bulkRetry = BulkRetryJobsUseCase(jobsRepository),
        bulkCancel = BulkCancelJobsUseCase(jobsRepository),
        bulkDelete = BulkDeleteJobsUseCase(jobsRepository),
        listRecurring = ListRecurringJobsUseCase(recurringRepository),
        enableRecurring = EnableRecurringJobUseCase(recurringRepository),
        disableRecurring = DisableRecurringJobUseCase(recurringRepository),
        triggerRecurring = TriggerRecurringJobUseCase(recurringRepository),
        getStatsOverview = GetStatsOverviewUseCase(statsRepository),
        getUpcoming = GetUpcomingUseCase(upcomingRepository),
        listWorkers = ListWorkersUseCase(workersRepository),
        listPausedTypes = ListPausedTypesUseCase(typesRepository),
        listKnownTypes = ListKnownTypesUseCase(typesRepository),
        listQueuesHealth = ListQueuesHealthUseCase(queueHealthRepository),
        pauseType = PauseTypeUseCase(typesRepository),
        unpauseType = UnpauseTypeUseCase(typesRepository),
        listTypeStats = ListTypeStatsUseCase(typeStatsRepository),
        eventStream = eventStream,
        connectionStatus = connectionStatus,
        webHistoryController = webHistory,
        deepLinkPath = deepLink,
    )

    ComposeViewport(document.body!!) {
        RootContent(root)
    }
}
