-- Initial schema for TaskScheduler. Mirrors DESIGN.md section 6.
-- All timestamps are TIMESTAMPTZ. All payloads are JSONB.

CREATE EXTENSION IF NOT EXISTS pgcrypto;

------------------------------------------------------------------------
-- job: core entity. One row per enqueue, state machine driven.
------------------------------------------------------------------------
CREATE TABLE job (
    id                   UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    state                TEXT         NOT NULL,
    queue                TEXT         NOT NULL,
    priority             INT          NOT NULL DEFAULT 0  CHECK (priority BETWEEN 0 AND 10),
    payload_type         TEXT         NOT NULL,
    payload_json         JSONB        NOT NULL,

    scheduled_at         TIMESTAMPTZ  NULL,
    attempts             INT          NOT NULL DEFAULT 0,
    max_attempts         INT          NOT NULL DEFAULT 3,
    timeout_seconds      INT          NULL,

    -- Distributed worker lock (see DESIGN.md section 13.4)
    locked_by            TEXT         NULL,
    locked_until         TIMESTAMPTZ  NULL,

    pending_deps         INT          NOT NULL DEFAULT 0,
    version              INT          NOT NULL DEFAULT 0,

    -- Producer-side dedup (DESIGN.md 17.4)
    idempotency_key      TEXT         NULL,

    -- Routing (DESIGN.md 22.2)
    target_node          TEXT         NULL,
    target_tag           TEXT         NULL,

    -- Progress (DESIGN.md 22.3)
    progress             REAL         NULL,
    progress_msg         TEXT         NULL,
    progress_updated_at  TIMESTAMPTZ  NULL,

    -- Stats (DESIGN.md 22.4)
    started_at           TIMESTAMPTZ  NULL,
    duration_ms          BIGINT       NULL,

    -- In-progress cancellation (DESIGN.md 22.7)
    cancel_requested_at  TIMESTAMPTZ  NULL,
    cancel_requested_by  TEXT         NULL,

    -- MDC + OTel propagation (DESIGN.md 22.11)
    context_json         JSONB        NULL,

    created_at           TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at           TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX job_state_scheduled_at_idx  ON job (state, scheduled_at);
CREATE INDEX job_state_locked_until_idx  ON job (state, locked_until);
CREATE INDEX job_payload_type_state_idx  ON job (payload_type, state);
CREATE INDEX job_updated_at_idx          ON job (updated_at);

-- Unique partial index for enqueueOnce (DESIGN.md 17.4)
CREATE UNIQUE INDEX job_idempotency_key_active_idx ON job (idempotency_key)
    WHERE state IN ('AWAITING_DEPS', 'SCHEDULED', 'ENQUEUED', 'PROCESSING', 'AWAITING_RETRY')
      AND idempotency_key IS NOT NULL;

------------------------------------------------------------------------
-- job_event: audit log + dashboard timeline. CASCADE with job.
------------------------------------------------------------------------
CREATE TABLE job_event (
    id           BIGSERIAL    PRIMARY KEY,
    job_id       UUID         NOT NULL REFERENCES job(id) ON DELETE CASCADE,
    event_type   TEXT         NOT NULL,
    prev_state   TEXT         NULL,
    new_state    TEXT         NULL,
    actor        TEXT         NULL,         -- user for MANUAL_*, NULL for system
    error_msg    TEXT         NULL,
    error_stack  TEXT         NULL,
    occurred_at  TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX job_event_job_id_occurred_at_idx ON job_event (job_id, occurred_at);

------------------------------------------------------------------------
-- job_dependency: DAG edges parent -> child. CASCADE with job.
------------------------------------------------------------------------
CREATE TABLE job_dependency (
    parent_id   UUID  NOT NULL REFERENCES job(id) ON DELETE CASCADE,
    child_id    UUID  NOT NULL REFERENCES job(id) ON DELETE CASCADE,
    on_failure  TEXT  NOT NULL DEFAULT 'PROPAGATE_FAILURE',
    PRIMARY KEY (parent_id, child_id)
);

CREATE INDEX job_dependency_child_id_idx ON job_dependency (child_id);

------------------------------------------------------------------------
-- recurring_job: cron-driven recurring definitions.
------------------------------------------------------------------------
CREATE TABLE recurring_job (
    id                  TEXT         PRIMARY KEY,
    cron                TEXT         NOT NULL,
    timezone            TEXT         NULL,                 -- IANA TZ, NULL = UTC
    misfire_policy      TEXT         NOT NULL DEFAULT 'CATCH_UP_ONE',
    queue               TEXT         NOT NULL,
    priority            INT          NOT NULL DEFAULT 0  CHECK (priority BETWEEN 0 AND 10),
    target_node         TEXT         NULL,
    target_tag          TEXT         NULL,
    payload_type        TEXT         NOT NULL,
    payload_json        JSONB        NOT NULL,
    last_triggered_at   TIMESTAMPTZ  NULL,
    next_trigger_at     TIMESTAMPTZ  NOT NULL,
    enabled             BOOLEAN      NOT NULL DEFAULT TRUE
);

CREATE INDEX recurring_job_next_trigger_idx
    ON recurring_job (next_trigger_at)
    WHERE enabled = TRUE;

------------------------------------------------------------------------
-- outbox: transactional outbox for PG -> Rabbit (DESIGN.md 4.1). CASCADE with job.
------------------------------------------------------------------------
CREATE TABLE outbox (
    id            BIGSERIAL    PRIMARY KEY,
    job_id        UUID         NOT NULL REFERENCES job(id) ON DELETE CASCADE,
    routing_key   TEXT         NOT NULL,
    priority      INT          NOT NULL DEFAULT 0  CHECK (priority BETWEEN 0 AND 10),
    delay_ms      BIGINT       NOT NULL DEFAULT 0,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    published_at  TIMESTAMPTZ  NULL
);

CREATE INDEX outbox_unpublished_idx
    ON outbox (created_at)
    WHERE published_at IS NULL;

------------------------------------------------------------------------
-- worker: heartbeat registry for user-app worker nodes (DESIGN.md 13.4).
------------------------------------------------------------------------
CREATE TABLE worker (
    node_id          TEXT         PRIMARY KEY,
    host             TEXT         NOT NULL,
    tags             TEXT[]       NOT NULL DEFAULT '{}',
    last_heartbeat   TIMESTAMPTZ  NOT NULL,
    started_at       TIMESTAMPTZ  NOT NULL,
    in_flight_count  INT          NOT NULL DEFAULT 0
);

CREATE INDEX worker_last_heartbeat_idx ON worker (last_heartbeat);

------------------------------------------------------------------------
-- idempotency_log: handler-side dedup (DESIGN.md 17.3). NO FK to job —
-- TTL is independent so external API idempotency keys outlive the job.
------------------------------------------------------------------------
CREATE TABLE idempotency_log (
    job_id       UUID         NOT NULL,
    action       TEXT         NOT NULL DEFAULT 'default',
    occurred_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    PRIMARY KEY (job_id, action)
);

CREATE INDEX idempotency_log_occurred_at_idx ON idempotency_log (occurred_at);

------------------------------------------------------------------------
-- job_type_pause: UI "feature flag" for job types (DESIGN.md 22.1).
------------------------------------------------------------------------
CREATE TABLE job_type_pause (
    payload_type   TEXT         PRIMARY KEY,
    paused_since   TIMESTAMPTZ  NOT NULL,
    paused_by      TEXT         NOT NULL,
    reason         TEXT         NULL
);
