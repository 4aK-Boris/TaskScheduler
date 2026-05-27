package cs.trade.scheduler.dashboard.web.presentation.screens.stats

import com.arkivanov.decompose.ComponentContext
import com.arkivanov.decompose.value.MutableValue
import com.arkivanov.decompose.value.Value
import com.arkivanov.decompose.value.update
import cs.trade.scheduler.core.frontend.BaseComponent
import cs.trade.scheduler.dashboard.web.data.connection.EventStream
import cs.trade.scheduler.dashboard.web.domain.usecases.GetStatsOverviewUseCase
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch

public class DefaultStatsComponent(
    componentContext: ComponentContext,
    private val getOverview: GetStatsOverviewUseCase,
    private val events: EventStream,
    private val onBack: () -> Unit,
) : BaseComponent(componentContext), StatsComponent {

    private val _model = MutableValue(StatsComponent.Model(loading = true))
    override val model: Value<StatsComponent.Model> = _model

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
            getOverview().fold(
                onSuccess = { overview ->
                    _model.update { it.copy(overview = overview, loading = false, error = null) }
                },
                onFailure = { t ->
                    _model.update { it.copy(loading = false, error = t.message ?: "Failed to load") }
                },
            )
        }
    }

    // Stats counts every state and every worker — every event type can shift a number,
    // so subscribe to the whole firehose and let the debouncer collapse the burst.
    // 500ms here (vs 200ms elsewhere) since stats are aggregate and a half-second lag
    // is invisible to a human watching the page.
    private fun subscribeToEvents() {
        scope.launch {
            events.events.collect { _ -> refreshSignal.tryEmit(Unit) }
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

    // Background ticks don't flip the loading flag — the gauges keep their last value
    // until the new snapshot arrives. Stops the page from constantly flashing while a
    // busy queue churns through jobs.
    private fun refreshSilently() {
        scope.launch {
            getOverview().fold(
                onSuccess = { overview ->
                    _model.update { it.copy(overview = overview, error = null) }
                },
                onFailure = { /* keep prior snapshot on transient errors */ },
            )
        }
    }

    private companion object {
        const val REFRESH_DEBOUNCE_MS = 500L
    }
}
