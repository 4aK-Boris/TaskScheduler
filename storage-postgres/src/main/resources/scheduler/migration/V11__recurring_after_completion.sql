-- Continuous recurring definitions have no cron. Existing schedules keep their behaviour.
ALTER TABLE recurring_job
    ADD COLUMN repeat_after_completion BOOLEAN NOT NULL DEFAULT FALSE;

COMMENT ON COLUMN recurring_job.repeat_after_completion IS
    'Fire immediately when no non-terminal run remains; cron/misfire/overlap do not apply.';

-- Only live runs matter on the frequent completion path; avoid scanning retained history.
CREATE INDEX job_recurring_active_idx ON job (recurring_id)
    WHERE recurring_id IS NOT NULL
      AND state IN ('SCHEDULED', 'ENQUEUED', 'PROCESSING', 'AWAITING_RETRY', 'AWAITING_DEPS');

-- Both JVM and Python workers update this table. NOTIFY is delivered only after commit,
-- so infra sees the terminal state before checking whether the next run may be enqueued.
CREATE FUNCTION notify_recurring_ready() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF EXISTS (SELECT 1 FROM recurring_job
               WHERE id = NEW.recurring_id AND repeat_after_completion AND enabled) THEN
        PERFORM pg_notify('recurring_ready', '');
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER job_recurring_ready
    AFTER UPDATE OF state ON job
    FOR EACH ROW
    WHEN (NEW.recurring_id IS NOT NULL
          AND NEW.state IN ('SUCCEEDED', 'FAILED', 'CANCELLED')
          AND OLD.state IS DISTINCT FROM NEW.state)
    EXECUTE FUNCTION notify_recurring_ready();

CREATE FUNCTION notify_continuous_definition_ready() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.repeat_after_completion AND NEW.enabled THEN
        PERFORM pg_notify('recurring_ready', '');
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER continuous_definition_ready
    AFTER INSERT OR UPDATE OF repeat_after_completion, enabled ON recurring_job
    FOR EACH ROW EXECUTE FUNCTION notify_continuous_definition_ready();
