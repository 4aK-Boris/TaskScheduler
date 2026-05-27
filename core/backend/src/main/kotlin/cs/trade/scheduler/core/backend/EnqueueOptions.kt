package cs.trade.scheduler.core.backend

import cs.trade.scheduler.core.backend.handler.Job
import cs.trade.scheduler.core.backend.handler.retry.RetryPolicy
import cs.trade.scheduler.shared.MisfirePolicy
import cs.trade.scheduler.shared.OnFailure
import kotlin.time.Duration

/**
 * Per-call overrides for [Scheduler.enqueue] and friends. All fields are optional —
 * null/0/false means "use defaults from handler / global config".
 */
public data class EnqueueOptions(
    val queue: String? = null,                  // null = default queue
    val priority: Int? = null,                  // 0..10, null = handler default → 0
    val timeout: Duration? = null,              // null = scheduler default (5 min)
    val maxAttempts: Int? = null,
    val retryPolicy: RetryPolicy? = null,
    val targetNode: String? = null,
    val targetTag: String? = null,
    val targetQualifier: String? = null,        // Koin qualifier for function-ref API
    val captureContext: Boolean = true,         // MDC + OTel (DESIGN.md 22.11)
    val onParentFailure: OnFailure = OnFailure.PROPAGATE_FAILURE,
)

/**
 * Declarative recurring-job definition. Pass to [Scheduler.recurring]. See DESIGN.md 8.5.
 */
public data class RecurringDefinition(
    val id: String,
    val cron: String,
    val job: Job,
    val timezone: String? = null,
    val misfirePolicy: MisfirePolicy = MisfirePolicy.CATCH_UP_ONE,
    val queue: String = "default",
    val priority: Int = 0,
    val targetNode: String? = null,
    val targetTag: String? = null,
)
