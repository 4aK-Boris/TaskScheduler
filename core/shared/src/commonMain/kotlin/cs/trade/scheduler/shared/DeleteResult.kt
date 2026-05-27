package cs.trade.scheduler.shared

import kotlinx.serialization.Serializable

/**
 * Outcome of [cs.trade.scheduler.core.backend.Scheduler.delete] — operator-initiated
 * MANUAL_DELETE action (DESIGN.md 18.6). Only terminal-state rows are deletable; the
 * action runs the configured ArchivalSink first so audit trails survive even when the
 * DB row goes away.
 *
 *  - [DELETED] — row was terminal (SUCCEEDED / FAILED / CANCELLED), archived (if a
 *    non-Noop sink is wired) and removed. `job_event`, `job_dependency`, `outbox`
 *    children cascade-delete with it.
 *  - [NOT_TERMINAL] — row is still in flight. Operator must cancel first or wait.
 *  - [NOT_FOUND] — no row with that id.
 */
@Serializable
public enum class DeleteResult {
    DELETED,
    NOT_TERMINAL,
    NOT_FOUND,
}
