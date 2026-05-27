package cs.trade.scheduler.storage.postgres.domain.repositories

import cs.trade.scheduler.storage.postgres.domain.models.RecurringJobRow
import kotlin.time.Instant

/**
 * CRUD for `recurring_job`. See DESIGN.md 7.5.
 *
 * The `upsert` semantic preserves operational state (`lastTriggeredAt`, `enabled`) on
 * an idempotent re-registration — only the definition fields (cron, queue, payload, …)
 * get refreshed. Without that, restarting the user app would lose the firing history.
 */
public interface RecurringJobRepository {

    /** INSERT ... ON CONFLICT (id) DO UPDATE — only definition fields, not state. */
    public suspend fun upsert(row: RecurringJobRow)

    public suspend fun findById(id: String): RecurringJobRow?

    /**
     * `enabled = TRUE AND next_trigger_at <= now`, ordered by `next_trigger_at` ASC.
     * Used by `RecurringScheduler` to pull due rows.
     */
    public suspend fun findDue(now: Instant, limit: Int): List<RecurringJobRow>

    /**
     * CAS update: bump `last_triggered_at = firedAt`, `next_trigger_at = next`, but only
     * if the row's current `last_triggered_at` still equals [expectedLastTriggeredAt]
     * (NULL-safe via IS NOT DISTINCT FROM semantic). Returns true on success, false on
     * a race (another infra replica already fired).
     */
    public suspend fun markFiredAndScheduleNext(
        id: String,
        expectedLastTriggeredAt: Instant?,
        firedAt: Instant,
        next: Instant,
    ): Boolean

    /** Set `enabled = false`. Returns true if the row existed. */
    public suspend fun disable(id: String): Boolean

    /** Set `enabled = true`. Returns true if the row existed. */
    public suspend fun enable(id: String): Boolean

    /** All recurring definitions ordered by id ASC. Capped by [limit]. */
    public suspend fun findAll(limit: Int = 500): List<RecurringJobRow>
}
