-- Concurrency policy for enqueueOnce + recurring overlap-guard (DESIGN.md 17.4 / 8.5).
--
-- The original `job_idempotency_key_active_idx` forbids two active rows per key (it covers
-- every non-terminal state, AWAITING_DEPS included). That blocks "one running + one queued
-- successor", which REPLACE / ENQUEUE_AFTER need. Split it into two slots:
--   * leader    — the running / queued-to-run row (SCHEDULED/ENQUEUED/PROCESSING/AWAITING_RETRY)
--   * successor — at most one parked row (AWAITING_DEPS) waiting behind the leader
-- so a key has at most one leader AND at most one successor — never two concurrent runs.
DROP INDEX IF EXISTS job_idempotency_key_active_idx;

CREATE UNIQUE INDEX job_idem_leader_idx ON job (idempotency_key)
    WHERE idempotency_key IS NOT NULL
      AND state IN ('SCHEDULED', 'ENQUEUED', 'PROCESSING', 'AWAITING_RETRY');

CREATE UNIQUE INDEX job_idem_successor_idx ON job (idempotency_key)
    WHERE idempotency_key IS NOT NULL
      AND state = 'AWAITING_DEPS';

-- Recurring overlap-guard: ALLOW (default, current behaviour) / SKIP / REPLACE.
ALTER TABLE recurring_job
    ADD COLUMN overlap_policy TEXT NOT NULL DEFAULT 'ALLOW';
