package cs.trade.scheduler.dashboard.web.presentation.screens.workers

import com.arkivanov.decompose.ComponentContext
import com.arkivanov.decompose.value.MutableValue
import com.arkivanov.decompose.value.Value
import com.arkivanov.decompose.value.update
import cs.trade.scheduler.core.frontend.BaseComponent
import cs.trade.scheduler.dashboard.web.data.connection.EventStream
import cs.trade.scheduler.dashboard.web.data.persistence.BrowserStorage
import cs.trade.scheduler.dashboard.web.domain.usecases.ListWorkersUseCase
import cs.trade.scheduler.shared.events.WebSocketEvent
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.seconds

public class DefaultWorkersComponent(
    componentContext: ComponentContext,
    private val listUseCase: ListWorkersUseCase,
    private val events: EventStream,
    private val onBack: () -> Unit,
) : BaseComponent(componentContext), WorkersComponent {

    private val _model = MutableValue(
        WorkersComponent.Model(
            loading = true,
            autoRefreshSeconds = BrowserStorage.load(AUTO_KEY)?.toIntOrNull(),
            timeAbsolute = BrowserStorage.load(TIME_KEY) == "true",
        ),
    )
    override val model: Value<WorkersComponent.Model> = _model

    private val refreshSignal = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    private var autoRefreshJob: Job? = null

    init {
        refresh()
        subscribeToEvents()
        observeRefreshSignal()
        restartAutoRefresh()
    }

    override fun onRefreshClicked() = refresh()
    override fun onBackClicked() = onBack()

    override fun onAutoRefreshChanged(seconds: Int?) {
        _model.update { it.copy(autoRefreshSeconds = seconds) }
        BrowserStorage.save(AUTO_KEY, seconds?.toString() ?: "")
        restartAutoRefresh()
    }

    override fun onTimeModeChanged(absolute: Boolean) {
        _model.update { it.copy(timeAbsolute = absolute) }
        BrowserStorage.save(TIME_KEY, absolute.toString())
    }

    private fun restartAutoRefresh() {
        autoRefreshJob?.cancel()
        val seconds = _model.value.autoRefreshSeconds ?: return
        autoRefreshJob = scope.launch {
            while (true) {
                delay(seconds.seconds)
                if (!_model.value.loading) refreshSilently()
            }
        }
    }

    private fun refresh() {
        _model.update { it.copy(loading = true, error = null) }
        scope.launch {
            listUseCase().fold(
                onSuccess = { items ->
                    _model.update { it.copy(items = items, loading = false, error = null) }
                },
                onFailure = { t ->
                    _model.update { it.copy(loading = false, error = t.message ?: "Failed to load") }
                },
            )
        }
    }

    // Worker join/leave events from the dashboard server affect the roster directly.
    // In-flight counters only update via the per-node heartbeat (not WS-broadcast),
    // so a steadily-running cluster still gets a fresh count whenever any worker joins
    // or leaves — good enough for ops without spinning a periodic poll.
    private fun subscribeToEvents() {
        scope.launch {
            events.events.collect { event ->
                if (event is WebSocketEvent.WorkerJoin || event is WebSocketEvent.WorkerLeave) {
                    refreshSignal.tryEmit(Unit)
                }
            }
        }
    }

    @OptIn(FlowPreview::class)
    private fun observeRefreshSignal() {
        scope.launch {
            refreshSignal.debounce(REFRESH_DEBOUNCE_MS).collect {
                refreshSilently()
            }
        }
    }

    // No loading spinner on background refresh — the table just changes underneath the
    // user. A spinner on every event would feel like the page is constantly reloading.
    private fun refreshSilently() {
        scope.launch {
            listUseCase().fold(
                onSuccess = { items ->
                    _model.update { it.copy(items = items, error = null) }
                },
                onFailure = { /* keep last good snapshot on transient error */ },
            )
        }
    }

    private companion object {
        const val REFRESH_DEBOUNCE_MS = 200L
        const val AUTO_KEY = "dashboard.workers.autoRefresh"
        const val TIME_KEY = "dashboard.workers.timeAbsolute"
    }
}
