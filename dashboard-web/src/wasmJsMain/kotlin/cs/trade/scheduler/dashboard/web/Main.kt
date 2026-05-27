package cs.trade.scheduler.dashboard.web

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.window.ComposeViewport
import com.arkivanov.decompose.DefaultComponentContext
import com.arkivanov.essenty.lifecycle.LifecycleRegistry
import cs.trade.scheduler.dashboard.web.data.connection.ConnectionStatusStore
import cs.trade.scheduler.dashboard.web.data.connection.EventStream
import cs.trade.scheduler.dashboard.web.data.repositories.JobsRepositoryImpl
import cs.trade.scheduler.dashboard.web.data.repositories.RecurringRepositoryImpl
import cs.trade.scheduler.dashboard.web.data.repositories.StatsRepositoryImpl
import cs.trade.scheduler.dashboard.web.data.repositories.TypesRepositoryImpl
import cs.trade.scheduler.dashboard.web.data.repositories.WorkersRepositoryImpl
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
import cs.trade.scheduler.dashboard.web.presentation.root.DefaultRootComponent
import cs.trade.scheduler.dashboard.web.presentation.root.RootContent
import kotlinx.browser.document
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

// Wasm entry point. Composition root: hand-wires repositories and UseCases, then hands
// them to DefaultRootComponent. No DI container in the browser bundle to keep the wasm
// output small and the wiring obvious.
@OptIn(ExperimentalComposeUiApi::class)
fun main() {
    val lifecycle = LifecycleRegistry()

    val jobsRepository = JobsRepositoryImpl()
    val recurringRepository = RecurringRepositoryImpl()
    val statsRepository = StatsRepositoryImpl()
    val workersRepository = WorkersRepositoryImpl()
    val typesRepository = TypesRepositoryImpl()

    // Shared WS subscription for the tab's lifetime — owns the reconnect loop and
    // feeds the connection-status badge. SupervisorJob so a hiccup inside the loop
    // can never kill the rest of the app.
    val connectionStatus = ConnectionStatusStore()
    val eventStream = EventStream(connectionStatus)
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    eventStream.start(appScope)

    val root = DefaultRootComponent(
        componentContext = DefaultComponentContext(lifecycle = lifecycle),
        getJobsList = GetJobsListUseCase(jobsRepository),
        getJobDetail = GetJobDetailUseCase(jobsRepository),
        cancelJob = CancelJobUseCase(jobsRepository),
        retryJob = RetryJobUseCase(jobsRepository),
        deleteJob = DeleteJobUseCase(jobsRepository),
        rerouteJob = RerouteJobUseCase(jobsRepository),
        bulkRetry = BulkRetryJobsUseCase(jobsRepository),
        bulkCancel = BulkCancelJobsUseCase(jobsRepository),
        bulkDelete = BulkDeleteJobsUseCase(jobsRepository),
        listRecurring = ListRecurringJobsUseCase(recurringRepository),
        enableRecurring = EnableRecurringJobUseCase(recurringRepository),
        disableRecurring = DisableRecurringJobUseCase(recurringRepository),
        getStatsOverview = GetStatsOverviewUseCase(statsRepository),
        listWorkers = ListWorkersUseCase(workersRepository),
        listPausedTypes = ListPausedTypesUseCase(typesRepository),
        listKnownTypes = ListKnownTypesUseCase(typesRepository),
        pauseType = PauseTypeUseCase(typesRepository),
        unpauseType = UnpauseTypeUseCase(typesRepository),
        eventStream = eventStream,
        connectionStatus = connectionStatus,
    )

    ComposeViewport(document.body!!) {
        RootContent(root)
    }
}
