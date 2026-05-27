package cs.trade.scheduler.shared.dto

import kotlinx.serialization.Serializable

@Serializable
public data class ListWorkersResponse(
    val items: List<WorkerDto>,
)
