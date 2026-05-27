package cs.trade.scheduler.dashboard.web.presentation.screens.jobdetail

import com.arkivanov.decompose.value.Value
import cs.trade.scheduler.shared.CancelResult
import cs.trade.scheduler.shared.DeleteResult
import cs.trade.scheduler.shared.RerouteResult
import cs.trade.scheduler.shared.RetryResult
import cs.trade.scheduler.shared.dto.JobDetail

/**
 * Decompose component for the JobDetail screen. Loads the [JobDetail] by id at init,
 * exposes `cancel`, `retry`, and `delete` actions. Each action's outcome surfaces in
 * the matching `*Result` field so the UI can show a snackbar / banner.
 *
 * Delete uses a two-step inline confirm: first call flips [Model.confirmingDelete],
 * second call within the same screen session triggers the actual DELETE. Navigation
 * away resets the flag.
 */
public interface JobDetailComponent {
    public val model: Value<Model>

    public fun onBackClicked()
    public fun onRefreshClicked()
    public fun onCancelClicked()
    public fun onRetryClicked()
    public fun onDeleteClicked()
    public fun onDeleteConfirmCancelled()
    public fun onNeighbourClicked(jobId: String)
    public fun onRerouteFormToggled()
    public fun onRerouteNodeChanged(value: String)
    public fun onRerouteTagChanged(value: String)
    public fun onRerouteSubmit()

    public data class Model(
        val jobId: String,
        val detail: JobDetail? = null,
        val loading: Boolean = false,
        val error: String? = null,
        val cancelResult: CancelResult? = null,
        val cancelling: Boolean = false,
        val retryResult: RetryResult? = null,
        val retrying: Boolean = false,
        val deleteResult: DeleteResult? = null,
        val deleting: Boolean = false,
        /** First click on Delete arms the confirm; second click actually deletes. */
        val confirmingDelete: Boolean = false,
        /** Currently-paused payload types — used to render the PAUSED badge in the header. */
        val pausedTypes: Set<String> = emptySet(),
        val rerouteResult: RerouteResult? = null,
        val rerouting: Boolean = false,
        val rerouteFormOpen: Boolean = false,
        val rerouteNode: String = "",
        val rerouteTag: String = "",
    )
}
