package cs.trade.scheduler.dashboard.web.presentation.screens.joblist

import com.arkivanov.decompose.value.Value
import cs.trade.scheduler.shared.JobState
import cs.trade.scheduler.shared.dto.BulkActionResponse
import cs.trade.scheduler.shared.dto.JobView
import cs.trade.scheduler.shared.dto.QueueHealthDto

/**
 * Decompose component for the JobList screen. Plays the role of a ViewModel — there is no
 * separate `JobListViewModel` (see DESIGN.md section 3.4 / Decompose convention).
 *
 * `Model` is nested in the interface so the file holds the full screen API in one place.
 * Output callbacks (declared in [DefaultJobListComponent]'s constructor) bubble up to
 * the [RootComponent] for navigation — the component never knows about its parent.
 *
 * **Bulk selection:** [Model.selectedIds] tracks the checked rows for bulk retry / cancel /
 * delete. Selection survives WS-driven silent refresh (set intersected with the new visible
 * items so a row that disappeared isn't kept selected). Manual filter change clears it.
 */
public interface JobListComponent {
    public val model: Value<Model>

    public fun onRefreshClicked()
    public fun onJobClicked(jobId: String)
    public fun onStateFilterChanged(states: Set<JobState>)
    public fun onDlqOnlyToggled(dlqOnly: Boolean)
    public fun onQueueFilterChanged(queue: String)
    public fun onPayloadTypeFilterChanged(payloadType: String)

    public fun onPrevPageClicked()
    public fun onNextPageClicked()
    public fun onPageSizeChanged(size: Int)

    public fun onJobChecked(jobId: String, checked: Boolean)
    public fun onSelectAllVisibleClicked(selectAll: Boolean)
    public fun onClearSelection()

    public fun onBulkRetryClicked()
    public fun onBulkCancelClicked()
    public fun onBulkDeleteClicked()
    public fun onDismissBulkResult()

    public data class Model(
        val items: List<JobView> = emptyList(),
        val total: Long = 0L,
        val stateFilter: Set<JobState> = emptySet(),
        val queueFilter: String = "",
        val payloadTypeFilter: String = "",
        val page: Int = 0,
        val pageSize: Int = 100,
        val loading: Boolean = false,
        val error: String? = null,
        val selectedIds: Set<String> = emptySet(),
        val bulkInFlight: Boolean = false,
        val bulkActionLabel: String? = null,
        val bulkResult: BulkActionResponse? = null,
        // payload_types currently in job_type_pause — used to render the PAUSED badge.
        // Loaded at init + refreshed on JobTypePaused/Unpaused WS events.
        val pausedTypes: Set<String> = emptySet(),
        // All known payload types (job DISTINCT + paused) — drives the autocomplete
        // dropdown on the payloadType filter.
        val knownTypes: List<String> = emptyList(),
        /**
         * Dead-letter filter — when on, list is restricted to `attempts >= max_attempts`
         * rows. The auto-retry budget is exhausted; operator must intervene (MANUAL_RETRY,
         * bulk-retry, or delete). See DESIGN.md 18.6.
         */
        val dlqOnly: Boolean = false,
        // Per-queue backpressure snapshot driving the header badges. Refreshed every 15s
        // and alongside the main list refresh. Queues with NORMAL status are still
        // included; the badge composable handles the no-render gating.
        val queueHealth: List<QueueHealthDto> = emptyList(),
    )
}
