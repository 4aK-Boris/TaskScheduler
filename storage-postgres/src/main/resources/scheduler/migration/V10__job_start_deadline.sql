-- Optional latest start: existing definitions/jobs keep unlimited waiting.
ALTER TABLE job ADD COLUMN start_deadline TIMESTAMPTZ;
ALTER TABLE recurring_job ADD COLUMN max_start_delay_ms BIGINT
    CHECK (max_start_delay_ms >= 0);

COMMENT ON COLUMN job.start_deadline IS
    'Fixed latest start; workers cancel overdue pickups without invoking the handler. Retained across retries.';
COMMENT ON COLUMN recurring_job.max_start_delay_ms IS
    'Maximum lateness from the original cron slot in milliseconds. NULL disables expiry.';
