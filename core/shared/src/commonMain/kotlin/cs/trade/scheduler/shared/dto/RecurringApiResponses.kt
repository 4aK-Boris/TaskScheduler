package cs.trade.scheduler.shared.dto

import kotlinx.serialization.Serializable

// Wire DTOs for /api/recurring/*. Shared between dashboard-server and dashboard-web.

@Serializable
public data class ListRecurringJobsResponse(
    val items: List<RecurringJobDto>,
)

@Serializable
public data class ToggleRecurringResponse(
    val id: String,
    val enabled: Boolean,
)

// Manual "Run now": the recurring id that fired + the id of the one-off job it created.
@Serializable
public data class TriggerRecurringResponse(
    val id: String,
    val jobId: String,
)
