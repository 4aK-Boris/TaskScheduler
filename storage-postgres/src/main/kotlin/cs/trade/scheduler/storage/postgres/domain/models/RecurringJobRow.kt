package cs.trade.scheduler.storage.postgres.domain.models

import cs.trade.scheduler.shared.MisfirePolicy
import kotlin.time.Instant

/**
 * Domain model for a `recurring_job` row. Mirrors V1__initial_schema.sql.
 *
 * `id` is user-supplied (e.g. `"daily-billing"`) — primary key, idempotent re-registration.
 *
 * `nextTriggerAt` is what the RecurringScheduler loop reads to decide when to fire.
 * Updated in the same UPDATE that bumps `lastTriggeredAt`, so the two stay consistent.
 *
 * `timezone` is an IANA ID (e.g. `"Europe/Berlin"`). `null` means UTC. cron-utils
 * computes next executions in the supplied zone.
 */
public data class RecurringJobRow(
    val id: String,
    val cron: String,
    val timezone: String?,
    val misfirePolicy: MisfirePolicy,
    val queue: String,
    val priority: Int,
    val targetNode: String?,
    val targetTag: String?,
    val payloadType: String,
    val payloadJson: String,
    val lastTriggeredAt: Instant?,
    val nextTriggerAt: Instant,
    val enabled: Boolean,
)
