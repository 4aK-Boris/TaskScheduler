package cs.trade.scheduler.dashboard.web.presentation.screens.workers

import com.arkivanov.decompose.value.Value
import cs.trade.scheduler.shared.dto.WorkerDto

public interface WorkersComponent {
    public val model: Value<Model>

    public fun onRefreshClicked()
    public fun onBackClicked()

    public data class Model(
        val items: List<WorkerDto> = emptyList(),
        val loading: Boolean = false,
        val error: String? = null,
    )
}
