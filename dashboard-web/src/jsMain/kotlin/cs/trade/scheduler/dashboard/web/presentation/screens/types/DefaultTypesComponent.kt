package cs.trade.scheduler.dashboard.web.presentation.screens.types

import com.arkivanov.decompose.ComponentContext
import com.arkivanov.decompose.value.MutableValue
import com.arkivanov.decompose.value.Value
import com.arkivanov.decompose.value.update
import cs.trade.scheduler.core.frontend.BaseComponent
import cs.trade.scheduler.dashboard.web.data.connection.EventStream
import cs.trade.scheduler.dashboard.web.data.persistence.BrowserStorage
import cs.trade.scheduler.dashboard.web.domain.usecases.ListKnownTypesUseCase
import cs.trade.scheduler.dashboard.web.domain.usecases.ListPausedTypesUseCase
import cs.trade.scheduler.dashboard.web.domain.usecases.PauseTypeUseCase
import cs.trade.scheduler.dashboard.web.domain.usecases.UnpauseTypeUseCase
import cs.trade.scheduler.shared.events.WebSocketEvent
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.seconds

/**
 * Types screen — paused-only list (the server doesn't yet expose "all known types";
 * once /api/types lands, we'll extend Model to include the discovery list too).
 *
 * Live updates via WS [WebSocketEvent.JobTypePaused] / [WebSocketEvent.JobTypeUnpaused]:
 * pause/unpause from another tab or via direct API call updates this screen without a
 * manual refresh. Debounced 200ms so a burst of toggles coalesces into one reload
 * (matches Workers/Recurring screens).
 */
public class DefaultTypesComponent(
    componentContext: ComponentContext,
    private val listUseCase: ListPausedTypesUseCase,
    private val listKnownUseCase: ListKnownTypesUseCase,
    private val pauseUseCase: PauseTypeUseCase,
    private val unpauseUseCase: UnpauseTypeUseCase,
    private val events: EventStream,
    private val onBack: () -> Unit,
) : BaseComponent(componentContext), TypesComponent {

    private val _model = MutableValue(
        TypesComponent.Model(
            loading = true,
            autoRefreshSeconds = BrowserStorage.load(AUTO_KEY)?.toIntOrNull(),
            timeAbsolute = BrowserStorage.load(TIME_KEY) == "true",
        ),
    )
    override val model: Value<TypesComponent.Model> = _model

    private val refreshSignal = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    private var autoRefreshJob: Job? = null

    init {
        refresh()
        refreshKnownTypes()
        subscribeToEvents()
        observeRefreshSignal()
        restartAutoRefresh()
    }

    override fun onAutoRefreshChanged(seconds: Int?) {
        _model.update { it.copy(autoRefreshSeconds = seconds) }
        BrowserStorage.save(AUTO_KEY, seconds?.toString() ?: "")
        restartAutoRefresh()
    }

    override fun onTimeModeChanged(absolute: Boolean) {
        _model.update { it.copy(timeAbsolute = absolute) }
        BrowserStorage.save(TIME_KEY, absolute.toString())
    }

    // Polling fallback on top of the WS live-updates — useful if the socket drops.
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

    private fun refreshKnownTypes() {
        scope.launch {
            listKnownUseCase().fold(
                onSuccess = { types -> _model.update { it.copy(knownTypes = types) } },
                onFailure = { /* dropdown empty on failure — free-text still works */ },
            )
        }
    }

    override fun onRefreshClicked() = refresh()
    override fun onBackClicked() = onBack()

    override fun onPauseFormTypeChanged(value: String) {
        _model.update { it.copy(pauseFormType = value, error = null) }
    }

    override fun onPauseFormReasonChanged(value: String) {
        _model.update { it.copy(pauseFormReason = value) }
    }

    override fun onPauseSubmit() {
        val state = _model.value
        if (state.submitting) return
        val type = state.pauseFormType.trim()
        if (type.isEmpty()) {
            _model.update { it.copy(error = "Payload type required") }
            return
        }
        _model.update { it.copy(submitting = true, error = null) }
        scope.launch {
            val reason = state.pauseFormReason.trim().takeIf { it.isNotEmpty() }
            pauseUseCase(type, reason).fold(
                onSuccess = { ok ->
                    if (!ok) {
                        _model.update { it.copy(submitting = false, error = "Pause request rejected") }
                        return@fold
                    }
                    // Success — clear form, refresh list, drop the busy flag.
                    _model.update {
                        it.copy(submitting = false, pauseFormType = "", pauseFormReason = "", error = null)
                    }
                    refreshSilently()
                },
                onFailure = { t ->
                    _model.update { it.copy(submitting = false, error = t.message ?: "Failed to pause") }
                },
            )
        }
    }

    override fun onUnpauseClicked(payloadType: String) {
        if (_model.value.unpausingType != null) return
        _model.update { it.copy(unpausingType = payloadType, error = null) }
        scope.launch {
            unpauseUseCase(payloadType).fold(
                onSuccess = { removed ->
                    _model.update { it.copy(unpausingType = null) }
                    if (removed) refreshSilently()
                    // 404 → the row was already gone (someone else unpaused). Nothing to do.
                },
                onFailure = { t ->
                    _model.update { it.copy(unpausingType = null, error = t.message ?: "Failed to unpause") }
                },
            )
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

    private fun refreshSilently() {
        scope.launch {
            listUseCase().fold(
                onSuccess = { items -> _model.update { it.copy(items = items) } },
                onFailure = { /* keep last good snapshot */ },
            )
        }
    }

    // Other tabs (or direct API callers) pausing/unpausing types should refresh the list
    // here without the user having to click. Only pause/unpause events touch this screen.
    private fun subscribeToEvents() {
        scope.launch {
            events.events.collect { event ->
                if (event is WebSocketEvent.JobTypePaused || event is WebSocketEvent.JobTypeUnpaused) {
                    refreshSignal.tryEmit(Unit)
                    // Newly-paused type may not be in `job` DISTINCT (e.g. just-paused via
                    // API before any job of that type was enqueued) — refresh known set
                    // so the dropdown picks it up too.
                    refreshKnownTypes()
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

    private companion object {
        const val REFRESH_DEBOUNCE_MS = 200L
        const val AUTO_KEY = "dashboard.types.autoRefresh"
        const val TIME_KEY = "dashboard.types.timeAbsolute"
    }
}
