package cs.trade.scheduler.dashboard.web.presentation.screens.upcoming

import com.arkivanov.decompose.ComponentContext
import com.arkivanov.decompose.value.MutableValue
import com.arkivanov.decompose.value.Value
import com.arkivanov.decompose.value.update
import cs.trade.scheduler.core.frontend.BaseComponent
import cs.trade.scheduler.dashboard.web.data.connection.EventStream
import cs.trade.scheduler.dashboard.web.data.persistence.BrowserStorage
import cs.trade.scheduler.dashboard.web.domain.usecases.GetJobsListUseCase
import cs.trade.scheduler.shared.events.WebSocketEvent
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.seconds

public class DefaultUpcomingComponent(
    componentContext: ComponentContext,
    private val getJobsList: GetJobsListUseCase,
    private val events: EventStream,
    private val onBack: () -> Unit,
    private val onJobSelected: (jobId: String) -> Unit,
) : BaseComponent(componentContext), UpcomingComponent {

    private val _model = MutableValue(
        UpcomingComponent.Model(
            loading = true,
            windowMinutes = BrowserStorage.load(WINDOW_KEY)?.toIntOrNull()
                ?: UpcomingComponent.DEFAULT_WINDOW_MINUTES,
            autoRefreshSeconds = BrowserStorage.load(AUTO_KEY)?.toIntOrNull(),
            timeAbsolute = BrowserStorage.load(TIME_KEY) == "true",
        ),
    )
    override val model: Value<UpcomingComponent.Model> = _model

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
    override fun onJobClicked(jobId: String) = onJobSelected(jobId)

    override fun onWindowChanged(minutes: Int) {
        if (minutes == _model.value.windowMinutes) return
        _model.update { it.copy(windowMinutes = minutes) }
        BrowserStorage.save(WINDOW_KEY, minutes.toString())
        refresh()
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
        load(silent = false)
    }

    private fun refreshSilently() = load(silent = true)

    // The server orders by scheduled_at ASC and filters [now, now + window]; we just take the page.
    // A generous size cap (no pagination on the agenda) — a window with more than this many jobs is
    // already past "glanceable", and the operator can narrow the window.
    private fun load(silent: Boolean) {
        scope.launch {
            getJobsList(
                page = 0,
                size = PAGE_SIZE,
                scheduledWithinMinutes = _model.value.windowMinutes,
            ).fold(
                onSuccess = { resp ->
                    _model.update { it.copy(items = resp.items, loading = false, error = null) }
                },
                onFailure = { t ->
                    if (!silent) _model.update { it.copy(loading = false, error = t.message ?: "Failed to load") }
                    // silent path keeps the last good snapshot on a transient error
                },
            )
        }
    }

    // Jobs enter the window on schedule (JobCreated) and leave it on promotion/cancel
    // (JobStateChanged/JobDeleted) — refresh on those so the agenda stays current without polling.
    private fun subscribeToEvents() {
        scope.launch {
            events.events.collect { event ->
                val affects = event is WebSocketEvent.JobCreated ||
                    event is WebSocketEvent.JobStateChanged ||
                    event is WebSocketEvent.JobDeleted
                if (affects) refreshSignal.tryEmit(Unit)
            }
        }
    }

    @OptIn(FlowPreview::class)
    private fun observeRefreshSignal() {
        scope.launch {
            refreshSignal.debounce(REFRESH_DEBOUNCE_MS).collect { refreshSilently() }
        }
    }

    private companion object {
        const val REFRESH_DEBOUNCE_MS = 200L
        const val PAGE_SIZE = 200
        const val WINDOW_KEY = "dashboard.upcoming.windowMinutes"
        const val AUTO_KEY = "dashboard.upcoming.autoRefresh"
        const val TIME_KEY = "dashboard.upcoming.timeAbsolute"
    }
}
