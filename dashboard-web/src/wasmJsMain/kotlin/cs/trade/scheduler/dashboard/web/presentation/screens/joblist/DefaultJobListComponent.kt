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
import cs.trade.scheduler.dashboard.web.domain.usecases.ListQueuesHealthUseCase
import cs.trade.scheduler.shared.JobSortField
import cs.trade.scheduler.shared.JobState
import cs.trade.scheduler.shared.dto.BulkActionResponse
import cs.trade.scheduler.shared.events.WebSocketEvent
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.time.Duration.Companion.seconds

public class DefaultJobListComponent(
    componentContext: ComponentContext,
    private val getJobsList: GetJobsListUseCase,
    private val bulkRetry: BulkRetryJobsUseCase,
    private val bulkCancel: BulkCancelJobsUseCase,
    private val bulkDelete: BulkDeleteJobsUseCase,
    private val listPausedTypes: ListPausedTypesUseCase,
    private val listKnownTypes: ListKnownTypesUseCase,
    private val listQueuesHealth: ListQueuesHealthUseCase,
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
        refreshQueueHealth()
        subscribeToEvents()
        observeRefreshSignal()
        observeFilterChangeSignal()
        pollQueueHealth()
        restartAutoRefresh()
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

    private fun refreshQueueHealth() {
        scope.launch {
            listQueuesHealth().fold(
                onSuccess = { rows -> _model.update { it.copy(queueHealth = rows) } },
                onFailure = { /* keep previous snapshot — stale badge is better than disappearing one */ },
            )
        }
    }

    // The badge needs a refresh cadence independent of the list (the list refreshes on
    // every WS job event, but queue depth changes are slow-moving — 15s polling matches
    // the operator's perception of "current"). Refresh on JobCreated/JobStateChanged in
    // subscribeToEvents() handles the burst case.
    private fun pollQueueHealth() {
        scope.launch {
            while (true) {
                delay(QUEUE_HEALTH_POLL.seconds)
                refreshQueueHealth()
            }
        }
    }

    // Auto-refresh: re-fetch the list every N seconds when enabled. Cancel + relaunch on a
    // setting change. Skips a tick while a refresh is already in flight so ticks don't stack.
    private var autoRefreshJob: Job? = null

    private fun restartAutoRefresh() {
        autoRefreshJob?.cancel()
        val seconds = _model.value.autoRefreshSeconds ?: return
        autoRefreshJob = scope.launch {
            while (true) {
                delay(seconds.seconds)
                if (!_model.value.loading) refresh()
            }
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

    override fun onSortChanged(field: JobSortField) {
        _model.update { current ->
            // Three clicks cycle: sort (natural default) → flip → back to the default
            // (sortBy = null → server's updated_at DESC).
            val (by, asc) = when {
                current.sortBy != field -> field to defaultAscending(field)
                current.sortAscending == defaultAscending(field) -> field to !current.sortAscending
                else -> null to false
            }
            current.copy(sortBy = by, sortAscending = asc, selectedIds = emptySet(), page = 0)
        }
        saveFilter()
        refresh()
    }

    // Text columns read best A→Z; time/count columns best newest/highest first.
    private fun defaultAscending(field: JobSortField): Boolean = when (field) {
        JobSortField.QUEUE, JobSortField.TYPE, JobSortField.STATE -> true
        else -> false
    }

    override fun onAutoRefreshChanged(seconds: Int?) {
        _model.update { it.copy(autoRefreshSeconds = seconds) }
        saveFilter()
        restartAutoRefresh()
    }

    override fun onAgeModeChanged(absolute: Boolean) {
        _model.update { it.copy(ageAbsolute = absolute) }
        saveFilter()
    }

    override fun onStickToTopChanged(enabled: Boolean) {
        _model.update { it.copy(stickToTop = enabled) }
        saveFilter()
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
                sortBy = current.sortBy,
                sortAscending = current.sortAscending,
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
                if (affectsList) {
                    refreshSignal.tryEmit(Unit)
                    // Queue depth shifts on create/state-change; refresh badges alongside
                    // the list so they don't lag the visible row counts.
                    if (event is WebSocketEvent.JobCreated || event is WebSocketEvent.JobStateChanged) {
                        refreshQueueHealth()
                    }
                }
                if (event is WebSocketEvent.JobTypePaused || event is WebSocketEvent.JobTypeUnpaused) {
                    refreshPausedTypes()
                    refreshKnownTypes()
                }
                if (event is WebSocketEvent.JobProgress) {
                    // In-place row mutation — a JobProgress arrives up to once a second
                    // per running job, and a full REST refetch would (a) lose scroll
                    // position and (b) hammer the API. If the row isn't on the current
                    // page, the update is silently dropped.
                    applyProgressEvent(event)
                }
            }
        }
    }

    private fun applyProgressEvent(event: WebSocketEvent.JobProgress) {
        _model.update { state ->
            val idx = state.items.indexOfFirst { it.id == event.id }
            if (idx < 0) return@update state
            val updated = state.items[idx].copy(
                progress = event.progress,
                progressMsg = event.msg,
            )
            state.copy(items = state.items.toMutableList().also { it[idx] = updated })
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
            autoRefreshSeconds = state.autoRefreshSeconds,
            ageAbsolute = state.ageAbsolute,
            sortBy = state.sortBy?.name,
            sortAscending = state.sortAscending,
            stickToTop = state.stickToTop,
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
            autoRefreshSeconds = parsed.autoRefreshSeconds,
            ageAbsolute = parsed.ageAbsolute,
            sortBy = parsed.sortBy?.let { runCatching { JobSortField.valueOf(it) }.getOrNull() },
            sortAscending = parsed.sortAscending,
            stickToTop = parsed.stickToTop,
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
        // Defaults so a filter snapshot persisted before these settings existed still parses.
        val autoRefreshSeconds: Int? = null,
        val ageAbsolute: Boolean = false,
        val sortBy: String? = null,
        val sortAscending: Boolean = false,
        val stickToTop: Boolean = false,
    )

    private companion object {
        const val REFRESH_DEBOUNCE_MS = 200L
        const val FILTER_DEBOUNCE_MS = 300L
        const val FILTER_KEY = "dashboard.jobs.filter"
        const val MAX_PAGE_SIZE = 500
        const val QUEUE_HEALTH_POLL = 15
        val persistedJson: Json = Json { ignoreUnknownKeys = true }
    }
}
