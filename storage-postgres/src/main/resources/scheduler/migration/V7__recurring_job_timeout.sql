-- Per-recurring-job timeout override (RecurringDefinition.timeout) — see DESIGN.md 8.5 / 8.2.
-- NULL = fall back to the worker's defaultJobTimeout (unchanged behaviour for existing rows).
-- DefaultScheduler.recurring writes it here; FireDueRecurringJobsUseCase copies it into each
-- fired job's `timeout_seconds`, and the worker enforces it via withTimeout. Lets fast recurring
-- tasks get a tight bound without lowering the global default that long jobs (ML training) rely on.
ALTER TABLE recurring_job
    ADD COLUMN timeout_seconds INTEGER;
