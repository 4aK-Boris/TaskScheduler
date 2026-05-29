-- DAG dependency-progress propagation (Variant 1).
--
-- Persist the *initial* pending_deps so we can derive a child's progress from
-- (initial - current) / initial as parents complete. Without this denominator
-- we lose information the moment we decrement, so the dashboard could only say
-- "N deps left" instead of the nicer "50% of dependencies satisfied" bar.
--
-- DEFAULT 0 is safe: pre-V3 rows have no DAG history, and a 0 initial value
-- short-circuits the formula (we never call decrementPendingDeps on a
-- zero-deps job — it never lands in AWAITING_DEPS).
ALTER TABLE job
    ADD COLUMN initial_pending_deps INT NOT NULL DEFAULT 0;
