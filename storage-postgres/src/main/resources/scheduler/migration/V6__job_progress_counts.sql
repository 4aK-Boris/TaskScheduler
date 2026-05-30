-- Counting progress bar (JobContext.progressBar) — DESIGN.md 22.3.
-- The existing `progress FLOAT` stays the single source of the 0..1 fraction (and the
-- denominator for DAG rollup). These three counters are additive metadata the handler
-- reports via progressBar().succeeded()/failed(); NULL when a job used plain
-- updateProgress (or never reported), so the dashboard knows to fall back to a single bar.
ALTER TABLE job
    ADD COLUMN progress_succeeded BIGINT,
    ADD COLUMN progress_failed    BIGINT,
    ADD COLUMN progress_total     BIGINT;
