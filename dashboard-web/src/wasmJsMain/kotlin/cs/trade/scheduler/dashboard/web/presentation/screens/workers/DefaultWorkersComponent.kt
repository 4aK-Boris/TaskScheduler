package cs.trade.scheduler.dashboard.web.presentation.screens.workers

import com.arkivanov.decompose.ComponentContext
import com.arkivanov.decompose.value.MutableValue
import com.arkivanov.decompose.value.Value
import com.arkivanov.decompose.value.update
import cs.trade.scheduler.core.frontend.BaseComponent
import cs.trade.scheduler.dashboard.web.data.connection.EventStream
import cs.trade.scheduler.dashboard.web.domain.usecases.ListWorkersUseCase
import cs.trade.scheduler.shared.events.WebSocketEvent
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch

public class DefaultWorkersComponent(
    componentContext: ComponentContext,
    private val listUseCase: ListWorkersUseCase,
    private val events: EventStream,
    private val onBack: () -> Unit,
) : BaseComponent(componentContext), WorkersComponent {

    private val _model = MutableValue(WorkersComponent.Model(loading = true))
    override val model: Value<WorkersComponent.Model> = _model

    private val refreshSignal = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    init {
        refresh()
        subscribeToEvents()
        observeRefreshSignal()
    }

    override fun onRefreshClicked() = refresh()
    override fun onBackClicked() = onBack()

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
    }
}
