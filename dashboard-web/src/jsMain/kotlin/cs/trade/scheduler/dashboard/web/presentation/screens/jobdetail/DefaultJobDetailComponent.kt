package cs.trade.scheduler.dashboard.web.presentation.screens.jobdetail

import com.arkivanov.decompose.ComponentContext
import com.arkivanov.decompose.value.MutableValue
import com.arkivanov.decompose.value.Value
import com.arkivanov.decompose.value.update
import cs.trade.scheduler.core.frontend.BaseComponent
import cs.trade.scheduler.dashboard.web.data.connection.EventStream
import cs.trade.scheduler.dashboard.web.data.persistence.BrowserStorage
import cs.trade.scheduler.dashboard.web.domain.usecases.CancelJobUseCase
import cs.trade.scheduler.dashboard.web.domain.usecases.DeleteJobUseCase
import cs.trade.scheduler.dashboard.web.domain.usecases.GetJobDetailUseCase
import cs.trade.scheduler.dashboard.web.domain.usecases.ListPausedTypesUseCase
import cs.trade.scheduler.dashboard.web.domain.usecases.RerouteJobUseCase
import cs.trade.scheduler.dashboard.web.domain.usecases.RerunJobUseCase
import cs.trade.scheduler.dashboard.web.domain.usecases.RetryJobUseCase
import cs.trade.scheduler.shared.DeleteResult
import cs.trade.scheduler.shared.RerouteResult
import cs.trade.scheduler.shared.RetryMode
import cs.trade.scheduler.shared.events.EventFilter
import cs.trade.scheduler.shared.events.WebSocketEvent
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.seconds

public class DefaultJobDetailComponent(
    componentContext: ComponentContext,
    jobId: String,
    private val getDetail: GetJobDetailUseCase,
    private val cancelJob: CancelJobUseCase,
    private val retryJob: RetryJobUseCase,
    private val deleteJob: DeleteJobUseCase,
    private val rerouteJob: RerouteJobUseCase,
    private val rerunJob: RerunJobUseCase,
    private val listPausedTypes: ListPausedTypesUseCase,
    private val events: EventStream,
    /** Output: parent should pop the stack. */
    private val onBack: () -> Unit,
    /** Output: parent should push a new JobDetail screen for the clicked DAG neighbour. */
    private val onNavigateToJob: (jobId: String) -> Unit,
) : BaseComponent(componentContext), JobDetailComponent {

    private val _model = MutableValue(
        JobDetailComponent.Model(
            jobId = jobId,
            loading = true,
            autoRefreshSeconds = BrowserStorage.load(AUTO_KEY)?.toIntOrNull(),
            timeAbsolute = BrowserStorage.load(TIME_KEY) == "true",
        ),
    )
    override val model: Value<JobDetailComponent.Model> = _model

    private var autoRefreshJob: Job? = null

    init {
        refresh()
        refreshPausedTypes()
        subscribeToPauseEvents()
        subscribeToProgressEvents()
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

    // State transitions (PROCESSING → SUCCEEDED, new events) aren't pushed to this screen — only
    // progress is — so a periodic silent re-load is the way to watch a job evolve without clicking
    // Refresh. Silent = no loading flag (the body stays put; no skeleton flash).
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

    private fun refreshSilently() {
        scope.launch {
            getDetail(_model.value.jobId).fold(
                onSuccess = { detail -> _model.update { it.copy(detail = detail ?: it.detail) } },
                onFailure = { /* keep last good snapshot on transient error */ },
            )
        }
    }

    private fun refreshPausedTypes() {
        scope.launch {
            listPausedTypes().fold(
                onSuccess = { rows ->
                    _model.update { it.copy(pausedTypes = rows.map { r -> r.payloadType }.toSet()) }
                },
                onFailure = { /* badge becomes stale until next try — acceptable */ },
            )
        }
    }

    private fun subscribeToPauseEvents() {
        scope.launch {
            events.events.collect { event ->
                if (event is WebSocketEvent.JobTypePaused || event is WebSocketEvent.JobTypeUnpaused) {
                    refreshPausedTypes()
                }
            }
        }
    }

    private fun subscribeToProgressEvents() {
        // Server-side filtered subscription (DESIGN.md 9.2): a dedicated socket scoped to THIS
        // job, so we receive only its events instead of the whole-firehose progress flood we
        // used to sift client-side. The server-side filter does the `event.id == jobId` cut.
        val jobId = _model.value.jobId
        scope.launch {
            events.subscribe(EventFilter(jobIds = setOf(jobId))).collect { event ->
                if (event !is WebSocketEvent.JobProgress) return@collect
                // In-place mutation, no REST refetch. Progress events fire up to once a
                // second per job (handler-side throttle in JobContextImpl) — a full
                // refresh would re-render the whole timeline and lose any in-flight UI
                // interaction (e.g. an open reroute form).
                _model.update { m ->
                    val d = m.detail ?: return@update m
                    m.copy(
                        detail = d.copy(
                            job = d.job.copy(
                                progress = event.progress,
                                progressMsg = event.msg,
                                // Counting-bar counters ride along on counting events; a plain
                                // updateProgress event carries null for all three, so keep the
                                // existing values rather than wiping the split bar.
                                progressSucceeded = event.succeeded ?: d.job.progressSucceeded,
                                progressFailed = event.failed ?: d.job.progressFailed,
                                progressTotal = event.total ?: d.job.progressTotal,
                            ),
                        ),
                    )
                }
            }
        }
    }

    override fun onBackClicked() = onBack()

    override fun onRefreshClicked() = refresh()

    override fun onNeighbourClicked(jobId: String) = onNavigateToJob(jobId)

    override fun onCancelClicked() {
        if (_model.value.cancelling) return
        _model.update { it.copy(cancelling = true) }
        scope.launch {
            cancelJob(_model.value.jobId, by = null).fold(
                onSuccess = { result ->
                    _model.update { it.copy(cancelling = false, cancelResult = result) }
                    // Reload so the user sees the new state.
                    refresh()
                },
                onFailure = { t ->
                    _model.update { it.copy(cancelling = false, error = t.message ?: "Cancel failed") }
                },
            )
        }
    }

    override fun onRetryClicked(): Unit = doRetry(RetryMode.FRESH_BUDGET)

    override fun onRetryOnceClicked(): Unit = doRetry(RetryMode.ONCE)

    private fun doRetry(mode: RetryMode) {
        if (_model.value.retrying) return
        // Block double-clicks during cancel too — both actions touch the same row, ordering
        // gets weird if they interleave.
        if (_model.value.cancelling) return
        _model.update { it.copy(retrying = true, retryResult = null) }
        scope.launch {
            retryJob(_model.value.jobId, by = null, mode = mode).fold(
                onSuccess = { result ->
                    _model.update { it.copy(retrying = false, retryResult = result) }
                    refresh()
                },
                onFailure = { t ->
                    _model.update { it.copy(retrying = false, error = t.message ?: "Retry failed") }
                },
            )
        }
    }

    override fun onRerunClicked() {
        val s = _model.value
        // Share the same single-action gate as cancel/retry/delete — they all touch this job.
        if (s.rerunning || s.cancelling || s.retrying || s.deleting) return
        _model.update { it.copy(rerunning = true, error = null) }
        scope.launch {
            rerunJob(s.jobId).fold(
                onSuccess = { newId ->
                    _model.update { it.copy(rerunning = false) }
                    // Jump to the fresh copy — the original stays untouched. null = source gone (404).
                    if (newId != null) onNavigateToJob(newId)
                    else _model.update { it.copy(error = "Job not found — cannot re-run") }
                },
                onFailure = { t ->
                    _model.update { it.copy(rerunning = false, error = t.message ?: "Re-run failed") }
                },
            )
        }
    }

    override fun onDeleteClicked() {
        val state = _model.value
        if (state.deleting || state.cancelling || state.retrying) return
        if (!state.confirmingDelete) {
            _model.update { it.copy(confirmingDelete = true, deleteResult = null, error = null) }
            return
        }
        _model.update { it.copy(deleting = true, confirmingDelete = false) }
        scope.launch {
            deleteJob(state.jobId, by = null).fold(
                onSuccess = { result ->
                    _model.update { it.copy(deleting = false, deleteResult = result) }
                    if (result == DeleteResult.DELETED) {
                        // Row is gone — pop back to the list so the user isn't staring at a
                        // detail screen for a phantom id (next refresh would 404).
                        onBack()
                    }
                },
                onFailure = { t ->
                    _model.update { it.copy(deleting = false, error = t.message ?: "Delete failed") }
                },
            )
        }
    }

    override fun onDeleteConfirmCancelled() {
        if (_model.value.confirmingDelete) {
            _model.update { it.copy(confirmingDelete = false) }
        }
    }

    override fun onRerouteFormToggled() {
        _model.update {
            it.copy(
                rerouteFormOpen = !it.rerouteFormOpen,
                rerouteResult = null,
            )
        }
    }

    override fun onRerouteNodeChanged(value: String) {
        _model.update { it.copy(rerouteNode = value) }
    }

    override fun onRerouteTagChanged(value: String) {
        _model.update { it.copy(rerouteTag = value) }
    }

    override fun onRerouteSubmit() {
        val state = _model.value
        if (state.rerouting) return
        // At least one of node/tag must be filled — both empty would clear targets which
        // is a legitimate action but rare; require explicit confirmation via blank text.
        // For simplicity, accept submit regardless: blank-and-blank means "send to default queue".
        _model.update { it.copy(rerouting = true, rerouteResult = null) }
        scope.launch {
            rerouteJob(
                jobId = state.jobId,
                targetNode = state.rerouteNode.trim().takeIf { it.isNotEmpty() },
                targetTag = state.rerouteTag.trim().takeIf { it.isNotEmpty() },
            ).fold(
                onSuccess = { result ->
                    _model.update {
                        it.copy(
                            rerouting = false,
                            rerouteResult = result,
                            // Close the form on success — the user can re-open if they
                            // need to redirect again.
                            rerouteFormOpen = result != RerouteResult.REROUTED,
                            rerouteNode = if (result == RerouteResult.REROUTED) "" else it.rerouteNode,
                            rerouteTag = if (result == RerouteResult.REROUTED) "" else it.rerouteTag,
                        )
                    }
                    refresh()
                },
                onFailure = { t ->
                    _model.update {
                        it.copy(rerouting = false, error = t.message ?: "Reroute failed")
                    }
                },
            )
        }
    }

    private fun refresh() {
        _model.update { it.copy(loading = true, error = null) }
        scope.launch {
            getDetail(_model.value.jobId).fold(
                onSuccess = { detail ->
                    _model.update {
                        it.copy(
                            detail = detail,
                            loading = false,
                            error = if (detail == null) "Job not found" else null,
                        )
                    }
                },
                onFailure = { t ->
                    _model.update { it.copy(loading = false, error = t.message ?: "Failed to load") }
                },
            )
        }
    }

    private companion object {
        const val AUTO_KEY = "dashboard.jobDetail.autoRefresh"
        const val TIME_KEY = "dashboard.jobDetail.timeAbsolute"
    }
}
