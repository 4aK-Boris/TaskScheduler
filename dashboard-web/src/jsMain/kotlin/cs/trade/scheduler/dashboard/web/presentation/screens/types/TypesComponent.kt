package cs.trade.scheduler.dashboard.web.presentation.screens.types

import com.arkivanov.decompose.value.Value
import cs.trade.scheduler.shared.dto.TypePauseDto

public interface TypesComponent {
    public val model: Value<Model>

    public fun onRefreshClicked()
    public fun onBackClicked()

    public fun onPauseFormTypeChanged(value: String)
    public fun onPauseFormReasonChanged(value: String)
    public fun onPauseSubmit()

    public fun onUnpauseClicked(payloadType: String)

    /** Auto-refresh cadence — `null` = off (WS live-updates + manual Refresh only), else re-list every N seconds. */
    public fun onAutoRefreshChanged(seconds: Int?)

    /** Since column: `false` = relative ("2h ago"), `true` = absolute clock ("14:30:05"). */
    public fun onTimeModeChanged(absolute: Boolean)

    public data class Model(
        val items: List<TypePauseDto> = emptyList(),
        val loading: Boolean = false,
        val error: String? = null,
        val autoRefreshSeconds: Int? = null,
        val timeAbsolute: Boolean = false,
        // Form state for "Pause a new type". Lives inside the component so a wasm
        // recomposition doesn't lose the typed input.
        val pauseFormType: String = "",
        val pauseFormReason: String = "",
        val submitting: Boolean = false,
        // Per-row busy flag for the Unpause button. Single concurrent action at a time
        // — matches the Recurring screen's pattern.
        val unpausingType: String? = null,
        /**
         * All payload types the scheduler has seen (union of `job` table DISTINCT +
         * currently-paused). Drives the autocomplete dropdown next to the pause-form
         * text field. Empty list = no suggestions, free-text only — operator can still
         * pause a brand-new type by typing it in.
         */
        val knownTypes: List<String> = emptyList(),
    )
}
