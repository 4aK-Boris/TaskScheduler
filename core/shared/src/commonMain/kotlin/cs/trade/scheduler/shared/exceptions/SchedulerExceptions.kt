package cs.trade.scheduler.shared.exceptions

/**
 * Thrown by a JobHandler to skip retries and mark the job FAILED immediately.
 * See DESIGN.md section 16.2.
 *
 * Use when the failure is deterministic and re-running won't help (validation,
 * missing entity, malformed input, permanent downstream rejection).
 */
public class NonRetriableJobException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)

/** Thrown by dashboard/API layer when the requested job id is unknown. */
public class JobNotFoundException(
    public val jobId: String,
) : RuntimeException("Job $jobId not found")

/** Thrown when the requested recurring job id is unknown. */
public class RecurringJobNotFoundException(
    public val recurringId: String,
) : RuntimeException("Recurring job $recurringId not found")
