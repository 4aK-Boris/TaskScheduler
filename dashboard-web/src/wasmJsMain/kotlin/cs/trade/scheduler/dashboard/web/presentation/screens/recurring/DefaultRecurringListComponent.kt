package cs.trade.scheduler.dashboard.web.presentation.screens.recurring

import com.arkivanov.decompose.ComponentContext
import com.arkivanov.decompose.value.MutableValue
import com.arkivanov.decompose.value.Value
import com.arkivanov.decompose.value.update
import cs.trade.scheduler.core.frontend.BaseComponent
import cs.trade.scheduler.dashboard.web.data.connection.EventStream
import cs.trade.scheduler.dashboard.web.data.persistence.BrowserStorage
import cs.trade.scheduler.dashboard.web.domain.usecases.DisableRecurringJobUseCase
import cs.trade.scheduler.dashboard.web.domain.usecases.EnableRecurringJobUseCase
import cs.trade.scheduler.dashboard.web.domain.usecases.ListRecurringJobsUseCase
import cs.trade.scheduler.shared.events.WebSocketEvent
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.seconds

public class DefaultRecurringListComponent(
    componentContext: ComponentContext,
    private val listUseCase: ListRecurringJobsUseCase,
    private val enableUseCase: EnableRecurringJobUseCase,
    private val disableUseCase: DisableRecurringJobUseCase,
    private val events: EventStream,
    private val onBack: () -> Unit,
) : BaseComponent(componentContext), RecurringListComponent {

    private val _model = MutableValue(
        RecurringListComponent.Model(
            loading = true,
            ageAbsolute = BrowserStorage.load(AGE_KEY) == "true",
            autoRefreshSeconds = BrowserStorage.load(AUTO_KEY)?.toIntOrNull(),
        ),
    )
    override val model: Value<RecurringListComponent.Model> = _model

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

    override fun onAgeModeChanged(absolute: Boolean) {
        _model.update { it.copy(ageAbsolute = absolute) }
        BrowserStorage.save(AGE_KEY, absolute.toString())
    }

    override fun onAutoRefreshChanged(seconds: Int?) {
        _model.update { it.copy(autoRefreshSeconds = seconds) }
        BrowserStorage.save(AUTO_KEY, seconds?.toString() ?: "")
        restartAutoRefresh()
    }

    // Auto-refresh: silently re-list every N seconds when enabled; cancel + relaunch on change.
    private fun restartAutoRefresh() {
        autoRefreshJob?.cancel()
        val seconds = _model.value.autoRefreshSeconds ?: return
        autoRefreshJob = scope.launch {
            while (true) {
                delay(seconds.seconds)
                if (!_model.value.loading) refresh(keepLoadingFlag = false)
            }
        }
    }

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
        const val AGE_KEY = "dashboard.recurring.ageAbsolute"
        const val AUTO_KEY = "dashboard.recurring.autoRefresh"
    }
}
