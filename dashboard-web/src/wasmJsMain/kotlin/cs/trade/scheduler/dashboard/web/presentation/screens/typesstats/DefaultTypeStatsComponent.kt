package cs.trade.scheduler.dashboard.web.presentation.screens.typesstats

import com.arkivanov.decompose.ComponentContext
import com.arkivanov.decompose.value.MutableValue
import com.arkivanov.decompose.value.Value
import com.arkivanov.decompose.value.update
import cs.trade.scheduler.core.frontend.BaseComponent
import cs.trade.scheduler.dashboard.web.domain.usecases.ListTypeStatsUseCase
import cs.trade.scheduler.shared.dto.TypeStatsRange
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Drives the [TypeStatsComponent]. Loads on init, on range change, and on a 30s
 * background poll. No WebSocket subscription — type-stats aggregates change slowly
 * enough that a 30s refresh is plenty, and there's no per-event signal that would
 * matter for this view.
 */
public class DefaultTypeStatsComponent(
    componentContext: ComponentContext,
    private val listUseCase: ListTypeStatsUseCase,
    private val onBack: () -> Unit,
) : BaseComponent(componentContext), TypeStatsComponent {

    private val _model = MutableValue(TypeStatsComponent.Model(loading = true))
    override val model: Value<TypeStatsComponent.Model> = _model

    init {
        loadInitial()
        startPolling()
    }

    override fun onRangeChanged(range: TypeStatsRange) {
        if (_model.value.range == range) return
        _model.update { it.copy(range = range, loading = true, error = null) }
        fetch()
    }

    override fun onRefresh() {
        _model.update { it.copy(loading = true, error = null) }
        fetch()
    }

    override fun onBackClicked() = onBack()

    private fun loadInitial() = fetch()

    private fun fetch() {
        val range = _model.value.range
        scope.launch {
            listUseCase(range.toHours()).fold(
                onSuccess = { resp ->
                    _model.update { it.copy(items = resp.items, loading = false, error = null) }
                },
                onFailure = { t ->
                    _model.update { it.copy(loading = false, error = t.message ?: "Failed to load") }
                },
            )
        }
    }

    // Silent background refresh so the page doesn't go stale while an operator is
    // staring at it. Doesn't toggle `loading` (would cause the spinner to flash) and
    // swallows errors (keeps last good snapshot on transient network hiccups).
    private fun startPolling() {
        scope.launch {
            while (true) {
                delay(POLL_INTERVAL_MS)
                val range = _model.value.range
                listUseCase(range.toHours()).fold(
                    onSuccess = { resp ->
                        _model.update { it.copy(items = resp.items, error = null) }
                    },
                    onFailure = { /* keep last snapshot */ },
                )
            }
        }
    }

    private companion object {
        const val POLL_INTERVAL_MS = 30_000L
    }
}
