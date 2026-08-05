-- Link a fired job back to the recurring definition that produced it (DESIGN.md 8.5).
--
-- Until now the only trace of that link was the derived idempotency key
-- ("recurring:<id>"), and only under the SKIP / REPLACE overlap policies — the default ALLOW
-- fires with a NULL key, so a recurring definition had no way to find its own runs. The
-- dashboard needs it to show whether a definition is running right now and to open its current
-- (or last) run from the Recurring screen.
--
-- Nullable and unbackfilled on purpose: rows that already exist were fired before the column
-- did, and there is no reliable way to attribute them after the fact. They simply carry NULL;
-- a definition shows its run history from its next fire on.
ALTER TABLE job
    ADD COLUMN recurring_id TEXT NULL;

-- Serves "the latest run(s) of these definitions": the dashboard looks up one row per recurring
-- id, newest first. Partial — only a small fraction of jobs come from a recurring definition.
CREATE INDEX job_recurring_id_created_at_idx
    ON job (recurring_id, created_at DESC)
    WHERE recurring_id IS NOT NULL;
