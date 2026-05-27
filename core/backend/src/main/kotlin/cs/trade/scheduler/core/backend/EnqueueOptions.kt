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
    /**
     * When `true` AND used with [Scheduler.enqueueAfter], the child's effective priority
     * becomes `max(parents.map { it.priority })` — useful for an urgent fan-out where the
     * follow-up step should match the highest urgency of any parent.
     *
     * Override chain (DESIGN.md 19.3 extended for Phase 3):
     *  1. explicit [priority] in [EnqueueOptions] — wins unconditionally
     *  2. [inheritPriorityFromParents] = true — `max(parent.priority)` (skipped if 1)
     *  3. handler default → global default (0)
     *
     * Ignored on the non-DAG entry points (`enqueue`, `scheduleAt`, `enqueueOnce`) — they
     * have no parents to inherit from. Documented but not enforced via compile-time
     * separation; misuse silently no-ops.
     */
    val inheritPriorityFromParents: Boolean = false,
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
