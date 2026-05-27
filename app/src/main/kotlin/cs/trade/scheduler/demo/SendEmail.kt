package cs.trade.scheduler.demo

import cs.trade.scheduler.core.backend.handler.Job
import kotlinx.serialization.Serializable

/** Sample job payload — what gets serialised into `job.payload_json`. */
@Serializable
public data class SendEmail(
    val userId: Long,
    val template: String,
) : Job
