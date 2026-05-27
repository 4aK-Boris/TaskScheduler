package cs.trade.scheduler.dashboard.web.presentation.screens.joblist

import com.arkivanov.decompose.ComponentContext
import com.arkivanov.decompose.value.MutableValue
import com.arkivanov.decompose.value.Value
import com.arkivanov.decompose.value.update
import cs.trade.scheduler.core.frontend.BaseComponent
import cs.trade.scheduler.dashboard.web.data.connection.EventStream
import cs.trade.scheduler.dashboard.web.data.persistence.BrowserStorage
import cs.trade.scheduler.dashboard.web.domain.usecases.BulkCancelJobsUseCase
import cs.trade.scheduler.dashboard.web.domain.usecases.BulkDeleteJobsUseCase
import cs.trade.scheduler.dashboard.web.domain.usecases.BulkRetryJobsUseCase
import cs.trade.scheduler.dashboard.web.domain.usecases.GetJobsListUseCase
import cs.trade.scheduler.dashboard.web.domain.usecases.ListKnownTypesUseCase
import cs.trade.scheduler.dashboard.web.domain.usecases.ListPausedTypesUseCase
import cs.trade.scheduler.shared.JobState
import cs.trade.scheduler.shared.dto.BulkActionResponse
import cs.trade.scheduler.shared.events.WebSocketEvent
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

public class DefaultJobListComponent(
    componentContext: ComponentContext,
    private val getJobsList: GetJobsListUseCase,
    private val bulkRetry: BulkRetryJobsUseCase,
    private val bulkCancel: BulkCancelJobsUseCase,
    private val bulkDelete: BulkDeleteJobsUseCase,
    private val listPausedTypes: ListPausedTypesUseCase,
    private val listKnownTypes: ListKnownTypesUseCase,
    private val events: EventStream,
    private val onJobSelected: (jobId: String) -> Unit,
) : BaseComponent(componentContext), JobListComponent {

    private val _model = MutableValue(loadInitialModel())
    override val model: Value<JobListComponent.Model> = _model

    // Coalesces a burst of WebSocket events into a single refresh — busy queues fire
    // dozens of state changes per second; we don't need to re-fetch for each one.
    private val refreshSignal = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    // Filter changes are debounced — operator typing in queue/payloadType fields would
    // otherwise hit the API on every keystroke. 300ms feels instant but coalesces
    // multi-character bursts into one fetch.
    private val filterChangeSignal = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    init {
        refresh()
        refreshPausedTypes()
        refreshKnownTypes()
        subscribeToEvents()
        observeRefreshSignal()
        observeFilterChangeSignal()
    }

    private fun refreshPausedTypes() {
        scope.launch {
            listPausedTypes().fold(
                onSuccess = { rows ->
                    _model.update { it.copy(pausedTypes = rows.map { r -> r.payloadType }.toSet()) }
                },
                onFailure = { /* leave previous snapshot — paused badge becomes stale, acceptable */ },
            )
        }
    }

    private fun refreshKnownTypes() {
        scope.launch {
            listKnownTypes().fold(
                onSuccess = { types -> _model.update { it.copy(knownTypes = types) } },
                onFailure = { /* dropdown empty on failure — free-text still works */ },
            )
        }
    }

    override fun onRefreshClicked() {
        refresh()
    }

    override fun onStateFilterChanged(states: Set<JobState>) {
        _model.update { it.copy(stateFilter = states, selectedIds = emptySet(), page = 0) }
        saveFilter()
        refresh()
    }

    override fun onDlqOnlyToggled(dlqOnly: Boolean) {
        _model.update { it.copy(dlqOnly = dlqOnly, selectedIds = emptySet(), page = 0) }
        saveFilter()
        refresh()
    }

    override fun onQueueFilterChanged(queue: String) {
        _model.update { it.copy(queueFilter = queue, selectedIds = emptySet(), page = 0) }
        // Debounced — typing in a TextField shouldn't fire a GET per keystroke.
        filterChangeSignal.tryEmit(Unit)
    }

    override fun onPayloadTypeFilterChanged(payloadType: String) {
        _model.update { it.copy(payloadTypeFilter = payloadType, selectedIds = emptySet(), page = 0) }
        filterChangeSignal.tryEmit(Unit)
    }

    override fun onPrevPageClicked() {
        val current = _model.value
        if (current.page <= 0 || current.loading) return
        _model.update { it.copy(page = current.page - 1) }
        refresh()
    }

    override fun onNextPageClicked() {
        val current = _model.value
        val maxPage = ((current.total - 1) / current.pageSize).coerceAtLeast(0L).toInt()
        if (current.page >= maxPage || current.loading) return
        _model.update { it.copy(page = current.page + 1) }
        refresh()
    }

    override fun onPageSizeChanged(size: Int) {
        if (size <= 0) return
        _model.update { it.copy(pageSize = size, page = 0) }
        saveFilter()
        refresh()
    }

    override fun onJobClicked(jobId: String) {
        onJobSelected(jobId)
    }

    override fun onJobChecked(jobId: String, checked: Boolean) {
        _model.update {
            val next = if (checked) it.selectedIds + jobId else it.selectedIds - jobId
            it.copy(selectedIds = next)
        }
    }

    override fun onSelectAllVisibleClicked(selectAll: Boolean) {
        _model.update {
            val next = if (selectAll) it.items.map { row -> row.id }.toSet() else emptySet()
            it.copy(selectedIds = next)
        }
    }

    override fun onClearSelection() {
        _model.update { it.copy(selectedIds = emptySet(), bulkResult = null) }
    }

    override fun onDismissBulkResult() {
        _model.update { it.copy(bulkResult = null, bulkActionLabel = null) }
    }

    override fun onBulkRetryClicked() = runBulk("Retry") { ids, by -> bulkRetry(ids, by) }
    override fun onBulkCancelClicked() = runBulk("Cancel") { ids, by -> bulkCancel(ids, by) }
    override fun onBulkDeleteClicked() = runBulk("Delete") { ids, by -> bulkDelete(ids, by) }

    private fun runBulk(
        label: String,
        action: suspend (List<String>, String?) -> Result<BulkActionResponse>,
    ) {
        val state = _model.value
        if (state.bulkInFlight) return
        val ids = state.selectedIds.toList()
        if (ids.isEmpty()) return
        _model.update { it.copy(bulkInFlight = true, bulkActionLabel = label, bulkResult = null) }
        scope.launch {
            action(ids, null).fold(
                onSuccess = { response ->
                    _model.update {
                        it.copy(bulkInFlight = false, bulkResult = response, selectedIds = emptySet())
                    }
                    refresh()
                },
                onFailure = { t ->
                    _model.update { it.copy(bulkInFlight = false, error = t.message ?: "$label failed") }
                },
            )
        }
    }

    private fun refresh() {
        val current = _model.value
        val effectiveStates =
            if (current.dlqOnly && current.stateFilter.isEmpty()) setOf(JobState.FAILED)
            else current.stateFilter
        val dlqFilter = if (current.dlqOnly) true else null
        _model.update { it.copy(loading = true, error = null) }
        scope.launch {
            getJobsList(
                states = effectiveStates,
                queue = current.queueFilter.trim().takeIf { it.isNotEmpty() },
                payloadType = current.payloadTypeFilter.trim().takeIf { it.isNotEmpty() },
                page = current.page,
                size = current.pageSize,
                attemptsExhausted = dlqFilter,
            ).fold(
                onSuccess = { resp ->
                    _model.update {
                        val visibleIds = resp.items.map { row -> row.id }.toSet()
                        it.copy(
                            items = resp.items,
                            total = resp.total,
                            loading = false,
                            error = null,
                            selectedIds = it.selectedIds intersect visibleIds,
                        )
                    }
                },
                onFailure = { t ->
                    _model.update { it.copy(loading = false, error = t.message ?: "Failed to load jobs") }
                },
            )
        }
    }

    private fun subscribeToEvents() {
        scope.launch {
            events.events.collect { event ->
                val affectsList = event is WebSocketEvent.JobCreated ||
                    event is WebSocketEvent.JobStateChanged ||
                    event is WebSocketEvent.JobDeleted ||
                    event is WebSocketEvent.RecurringTriggered
                if (affectsList) refreshSignal.tryEmit(Unit)
                if (event is WebSocketEvent.JobTypePaused || event is WebSocketEvent.JobTypeUnpaused) {
                    refreshPausedTypes()
                    refreshKnownTypes()
                }
            }
        }
    }

    @OptIn(FlowPreview::class)
    private fun observeRefreshSignal() {
        scope.launch {
            refreshSignal.debounce(REFRESH_DEBOUNCE_MS).collect {
                refresh()
            }
        }
    }

    @OptIn(FlowPreview::class)
    private fun observeFilterChangeSignal() {
        scope.launch {
            filterChangeSignal.debounce(FILTER_DEBOUNCE_MS).collect {
                saveFilter()
                refresh()
            }
        }
    }

    // ---- Persistence -------------------------------------------------------

    private fun saveFilter() {
        val state = _model.value
        val snapshot = PersistedFilter(
            states = state.stateFilter.map { it.name },
            queue = state.queueFilter,
            payloadType = state.payloadTypeFilter,
            dlqOnly = state.dlqOnly,
            pageSize = state.pageSize,
        )
        BrowserStorage.save(FILTER_KEY, persistedJson.encodeToString(PersistedFilter.serializer(), snapshot))
    }

    private fun loadInitialModel(): JobListComponent.Model {
        val raw = BrowserStorage.load(FILTER_KEY) ?: return JobListComponent.Model(loading = true)
        val parsed = runCatching {
            persistedJson.decodeFromString(PersistedFilter.serializer(), raw)
        }.getOrNull() ?: return JobListComponent.Model(loading = true)
        // Defensive: a JobState enum value renamed between deploys would crash valueOf —
        // skip unknowns, keep recognised ones.
        val states = parsed.states.mapNotNull { runCatching { JobState.valueOf(it) }.getOrNull() }.toSet()
        return JobListComponent.Model(
            stateFilter = states,
            queueFilter = parsed.queue,
            payloadTypeFilter = parsed.payloadType,
            dlqOnly = parsed.dlqOnly,
            pageSize = parsed.pageSize.coerceIn(1, MAX_PAGE_SIZE),
            loading = true,
        )
    }

    @Serializable
    private data class PersistedFilter(
        val states: List<String>,
        val queue: String,
        val payloadType: String,
        val dlqOnly: Boolean,
        val pageSize: Int,
    )

    private companion object {
        const val REFRESH_DEBOUNCE_MS = 200L
        const val FILTER_DEBOUNCE_MS = 300L
        const val FILTER_KEY = "dashboard.jobs.filter"
        const val MAX_PAGE_SIZE = 500
        val persistedJson: Json = Json { ignoreUnknownKeys = true }
    }
}
