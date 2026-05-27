package cs.trade.scheduler.dashboard.web.presentation.screens.recurring

import com.arkivanov.decompose.ComponentContext
import com.arkivanov.decompose.value.MutableValue
import com.arkivanov.decompose.value.Value
import com.arkivanov.decompose.value.update
import cs.trade.scheduler.core.frontend.BaseComponent
import cs.trade.scheduler.dashboard.web.data.connection.EventStream
import cs.trade.scheduler.dashboard.web.domain.usecases.DisableRecurringJobUseCase
import cs.trade.scheduler.dashboard.web.domain.usecases.EnableRecurringJobUseCase
import cs.trade.scheduler.dashboard.web.domain.usecases.ListRecurringJobsUseCase
import cs.trade.scheduler.shared.events.WebSocketEvent
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch

public class DefaultRecurringListComponent(
    componentContext: ComponentContext,
    private val listUseCase: ListRecurringJobsUseCase,
    private val enableUseCase: EnableRecurringJobUseCase,
    private val disableUseCase: DisableRecurringJobUseCase,
    private val events: EventStream,
    private val onBack: () -> Unit,
) : BaseComponent(componentContext), RecurringListComponent {

    private val _model = MutableValue(RecurringListComponent.Model(loading = true))
    override val model: Value<RecurringListComponent.Model> = _model

    private val refreshSignal = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    init {
        refresh()
        subscribeToEvents()
        observeRefreshSignal()
    }

    override fun onRefreshClicked() = refresh()

    override fun onBackClicked() = onBack()

    override fun onToggleClicked(id: String, enable: Boolean) {
        if (_model.value.togglingId != null) return
        _model.update { it.copy(togglingId = id) }
        scope.launch {
            val result = if (enable) enableUseCase(id) else disableUseCase(id)
            result.onFailure { t ->
                _model.update { it.copy(togglingId = null, error = t.message) }
                return@launch
            }
            // Refresh the list to pick up the new enabled state.
            refresh(keepLoadingFlag = false)
            _model.update { it.copy(togglingId = null) }
        }
    }

    private fun refresh(keepLoadingFlag: Boolean = true) {
        if (keepLoadingFlag) _model.update { it.copy(loading = true, error = null) }
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

    // RecurringTriggered means cron just fired → lastTriggeredAt / nextTriggerAt
    // shifted on at least one row. Other event types don't touch this screen.
    private fun subscribeToEvents() {
        scope.launch {
            events.events.collect { event ->
                if (event is WebSocketEvent.RecurringTriggered) refreshSignal.tryEmit(Unit)
            }
        }
    }

    @OptIn(FlowPreview::class)
    private fun observeRefreshSignal() {
        scope.launch {
            refreshSignal.debounce(REFRESH_DEBOUNCE_MS).collect {
                refresh(keepLoadingFlag = false)
            }
        }
    }

    private companion object {
        const val REFRESH_DEBOUNCE_MS = 200L
    }
}
