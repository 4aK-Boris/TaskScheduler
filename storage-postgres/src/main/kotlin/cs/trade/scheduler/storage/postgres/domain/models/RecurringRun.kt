@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package cs.trade.scheduler.storage.postgres.domain.models

import cs.trade.scheduler.shared.JobState
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * The one run of a recurring definition the dashboard cares about: the live one if there is one,
 * otherwise the most recent finished one.
 *
 * A narrow projection rather than a whole [Job] — the Recurring screen shows a state chip, a
 * progress bar and a link, and pulling every payload for every definition to render that would be
 * wasteful (payload_json alone can be large).
 */
public data class RecurringRun(
    val recurringId: String,
    val jobId: Uuid,
    val state: JobState,

    /** Fraction 0..1 as last reported, or `null` if the handler never reported progress. */
    val progress: Float?,

    /** Counting-progress-bar figures ([cs.trade.scheduler.core.backend.handler.JobContext.progressBar]). */
    val progressSucceeded: Long?,
    val progressFailed: Long?,
    val progressTotal: Long?,

    val startedAt: Instant?,
    val durationMs: Long?,

    /** Last state change — for a terminal run this is when it finished. */
    val updatedAt: Instant,
) {
    /** True while the run still occupies the definition — i.e. it is (or is about to be) running. */
    public val isLive: Boolean get() = !state.isTerminal
}
