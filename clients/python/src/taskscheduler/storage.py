"""Postgres access layer — every SQL statement the SDK issues.

Postgres is the single source of truth for job state; RabbitMQ only carries a 16-byte job
id as a delivery hint. That means correctness lives here, in three rules taken from the
Kotlin implementation:

* **Optimistic locking.** Every state transition is ``WHERE id = %s AND version = %s`` and
  bumps ``version``. Zero rows updated means another node got there first — that is a
  normal outcome, not an error.
* **Outbox, never direct publish.** Enqueue writes a ``job`` row and an ``outbox`` row in
  one transaction. The infra leader polls the outbox and publishes to RabbitMQ, so a job is
  never visible to a worker before its row is committed.
* **Server clock.** All timestamps come from Postgres ``now()`` rather than the client, so
  leases stay comparable across nodes with drifting clocks.
"""

from __future__ import annotations

import asyncio
import json
import logging
import sys
import uuid
from collections import deque
from collections.abc import AsyncIterator, Sequence
from contextlib import asynccontextmanager
from datetime import datetime
from typing import Any

import psycopg
from psycopg import AsyncConnection
from psycopg.rows import DictRow, dict_row
from psycopg_pool import AsyncConnectionPool

from .config import REQUIRED_SCHEMA_VERSION, SchedulerConfig
from .errors import ConfigurationError, SchemaMismatchError
from .models import JobRow, JobState, OnFailure

log = logging.getLogger(__name__)

__all__ = ["Storage", "Conn", "MAX_ERROR_MSG_LEN", "MAX_ERROR_STACK_LEN"]

#: Connections always use ``dict_row``, so every result is keyed by column name.
Conn = AsyncConnection[DictRow]

#: Truncation limits for the audit trail, matching ``WorkerPool`` on the Kotlin side.
MAX_ERROR_MSG_LEN = 2_000
MAX_ERROR_STACK_LEN = 8_000

# ``payload_json::text`` on purpose: psycopg decodes JSONB into a dict, but the payload is
# opaque to this layer and the decoder wants the original document. Casting keeps the
# round-trip lossless and the column's Python type predictable.
_JOB_COLUMNS = """
    id, state, queue, priority, payload_type, payload_json::text AS payload_json,
    attempts, max_attempts, version, pending_deps, scheduled_at, timeout_seconds,
    locked_by, locked_until, target_node, target_tag, started_at,
    cancel_requested_at, created_at
"""


def _to_row(record: dict[str, Any]) -> JobRow:
    return JobRow(
        id=record["id"],
        state=JobState(record["state"]),
        queue=record["queue"],
        priority=record["priority"],
        payload_type=record["payload_type"],
        payload_json=record["payload_json"],
        attempts=record["attempts"],
        max_attempts=record["max_attempts"],
        version=record["version"],
        pending_deps=record["pending_deps"],
        scheduled_at=record["scheduled_at"],
        timeout_seconds=record["timeout_seconds"],
        locked_by=record["locked_by"],
        locked_until=record["locked_until"],
        target_node=record["target_node"],
        target_tag=record["target_tag"],
        started_at=record["started_at"],
        cancel_requested_at=record["cancel_requested_at"],
        created_at=record["created_at"],
    )


def _truncate(text: str | None, limit: int) -> str | None:
    if text is None:
        return None
    return text if len(text) <= limit else text[: limit - 1] + "…"


def _check_event_loop() -> None:
    """Fail fast on Windows' default event loop, which psycopg cannot drive.

    ``ProactorEventLoop`` (the asyncio default on Windows since 3.8) has no support for the
    socket polling psycopg needs, and the failure mode without this check is a silent
    30-second pool timeout with the real reason buried in a log warning.
    """
    if sys.platform != "win32":
        return
    loop = asyncio.get_running_loop()
    if isinstance(loop, asyncio.ProactorEventLoop):
        raise ConfigurationError(
            "psycopg cannot run on Windows' default ProactorEventLoop. Select the other "
            "loop before starting the scheduler:\n\n"
            "    import asyncio, sys\n"
            "    if sys.platform == 'win32':\n"
            "        asyncio.set_event_loop_policy(asyncio.WindowsSelectorEventLoopPolicy())\n\n"
            "then run your entrypoint with asyncio.run() as usual."
        )


class Storage:
    """Async Postgres repository. Open with :meth:`start`, close with :meth:`close`."""

    def __init__(self, config: SchedulerConfig) -> None:
        self._config = config
        self._pool: AsyncConnectionPool[Conn] | None = None

    # ---------------------------------------------------------------- lifecycle

    async def start(self) -> None:
        if self._pool is not None:
            return
        _check_event_loop()
        pool: AsyncConnectionPool[Conn] = AsyncConnectionPool(
            conninfo=self._config.dsn,
            min_size=self._config.min_pool_size,
            max_size=self._config.max_pool_size,
            kwargs={"row_factory": dict_row, "autocommit": True},
            open=False,
        )
        await pool.open(wait=True)
        self._pool = pool
        if self._config.check_schema:
            await self._verify_schema()

    async def close(self) -> None:
        if self._pool is not None:
            await self._pool.close()
            self._pool = None

    @asynccontextmanager
    async def connection(self) -> AsyncIterator[Conn]:
        if self._pool is None:
            raise RuntimeError("Storage.start() has not been awaited")
        async with self._pool.connection() as conn:
            yield conn

    @asynccontextmanager
    async def transaction(self) -> AsyncIterator[Conn]:
        """One connection wrapped in an explicit transaction."""
        async with self.connection() as conn, conn.transaction():
            yield conn

    async def _verify_schema(self) -> None:
        """Fail fast when infra has not applied the migrations this client needs."""
        async with self.connection() as conn:
            cur = await conn.execute("SELECT to_regclass('public.flyway_schema_history') AS t")
            row = await cur.fetchone()
            if row is None or row["t"] is None:
                raise SchemaMismatchError(
                    "no flyway_schema_history table found — start the Kotlin scheduler-infra "
                    "process first, it owns the schema (set check_schema=False to skip)"
                )
            cur = await conn.execute(
                """
                SELECT max(version::int) AS v
                FROM flyway_schema_history
                WHERE success AND version ~ '^[0-9]+$'
                """
            )
            row = await cur.fetchone()
            applied = (row["v"] if row and row["v"] is not None else 0)
            if applied < REQUIRED_SCHEMA_VERSION:
                raise SchemaMismatchError(
                    f"database is at schema V{applied} but this client needs at least "
                    f"V{REQUIRED_SCHEMA_VERSION} — upgrade scheduler-infra first"
                )

    # ---------------------------------------------------------------- producer

    async def insert_job(
        self,
        conn: Conn,
        *,
        job_id: uuid.UUID,
        state: JobState,
        queue: str,
        priority: int,
        payload_type: str,
        payload_json: str,
        max_attempts: int,
        timeout_seconds: int | None,
        scheduled_at: datetime | None = None,
        pending_deps: int = 0,
        idempotency_key: str | None = None,
        target_node: str | None = None,
        target_tag: str | None = None,
    ) -> None:
        await conn.execute(
            """
            INSERT INTO job (
                id, state, queue, priority, payload_type, payload_json,
                scheduled_at, attempts, max_attempts, timeout_seconds,
                pending_deps, initial_pending_deps, version,
                idempotency_key, target_node, target_tag,
                created_at, updated_at
            ) VALUES (
                %s, %s, %s, %s, %s, %s::jsonb,
                %s, 0, %s, %s,
                %s, %s, 0,
                %s, %s, %s,
                now(), now()
            )
            """,
            (
                job_id,
                state.value,
                queue,
                priority,
                payload_type,
                payload_json,
                scheduled_at,
                max_attempts,
                timeout_seconds,
                pending_deps,
                pending_deps,
                idempotency_key,
                target_node,
                target_tag,
            ),
        )

    async def insert_outbox(
        self,
        conn: Conn,
        *,
        job_id: uuid.UUID,
        routing_key: str,
        priority: int,
        delay_ms: int = 0,
    ) -> None:
        """Queue a dispatch. The infra leader publishes it to RabbitMQ within ~100ms."""
        await conn.execute(
            """
            INSERT INTO outbox (job_id, routing_key, priority, delay_ms, created_at)
            VALUES (%s, %s, %s, %s, now())
            """,
            (job_id, routing_key, priority, max(0, delay_ms)),
        )

    async def insert_event(
        self,
        conn: Conn,
        *,
        job_id: uuid.UUID,
        event_type: str,
        prev_state: JobState | None = None,
        new_state: JobState | None = None,
        actor: str | None = None,
        error_msg: str | None = None,
        error_stack: str | None = None,
    ) -> None:
        await conn.execute(
            """
            INSERT INTO job_event (
                job_id, event_type, prev_state, new_state,
                actor, error_msg, error_stack, occurred_at
            ) VALUES (%s, %s, %s, %s, %s, %s, %s, now())
            """,
            (
                job_id,
                event_type,
                prev_state.value if prev_state else None,
                new_state.value if new_state else None,
                actor,
                _truncate(error_msg, MAX_ERROR_MSG_LEN),
                _truncate(error_stack, MAX_ERROR_STACK_LEN),
            ),
        )

    async def insert_dependency(
        self,
        conn: Conn,
        *,
        parent_id: uuid.UUID,
        child_id: uuid.UUID,
        on_failure: OnFailure,
    ) -> None:
        await conn.execute(
            """
            INSERT INTO job_dependency (parent_id, child_id, on_failure)
            VALUES (%s, %s, %s)
            ON CONFLICT (parent_id, child_id) DO NOTHING
            """,
            (parent_id, child_id, on_failure.value),
        )

    async def find_by_id(self, conn: Conn, job_id: uuid.UUID) -> JobRow | None:
        cur = await conn.execute(f"SELECT {_JOB_COLUMNS} FROM job WHERE id = %s", (job_id,))
        record = await cur.fetchone()
        return _to_row(record) if record else None

    async def find_active_by_idempotency_key(
        self, conn: Conn, key: str
    ) -> JobRow | None:
        """The current leader for ``key`` — the row that owns the concurrency slot."""
        cur = await conn.execute(
            f"""
            SELECT {_JOB_COLUMNS} FROM job
            WHERE idempotency_key = %s
              AND state IN ('SCHEDULED', 'ENQUEUED', 'PROCESSING', 'AWAITING_RETRY')
            LIMIT 1
            """,
            (key,),
        )
        record = await cur.fetchone()
        return _to_row(record) if record else None

    async def upsert_recurring(
        self,
        conn: Conn,
        *,
        recurring_id: str,
        cron: str,
        payload_type: str,
        payload_json: str,
        queue: str,
        next_trigger_at: datetime,
        timezone_name: str | None = None,
        misfire_policy: str = "CATCH_UP_ONE",
        overlap_policy: str = "ALLOW",
        priority: int = 0,
        target_node: str | None = None,
        target_tag: str | None = None,
        timeout_seconds: int | None = None,
        enabled: bool = True,
    ) -> None:
        """Register (or update) a cron definition. The infra leader fires it — not this client."""
        await conn.execute(
            """
            INSERT INTO recurring_job (
                id, cron, timezone, misfire_policy, queue, priority, target_node, target_tag,
                payload_type, payload_json, next_trigger_at, enabled,
                timeout_seconds, overlap_policy
            ) VALUES (%s, %s, %s, %s, %s, %s, %s, %s, %s, %s::jsonb, %s, %s, %s, %s)
            ON CONFLICT (id) DO UPDATE SET
                cron = EXCLUDED.cron,
                timezone = EXCLUDED.timezone,
                misfire_policy = EXCLUDED.misfire_policy,
                queue = EXCLUDED.queue,
                priority = EXCLUDED.priority,
                target_node = EXCLUDED.target_node,
                target_tag = EXCLUDED.target_tag,
                payload_type = EXCLUDED.payload_type,
                payload_json = EXCLUDED.payload_json,
                next_trigger_at = EXCLUDED.next_trigger_at,
                enabled = EXCLUDED.enabled,
                timeout_seconds = EXCLUDED.timeout_seconds,
                overlap_policy = EXCLUDED.overlap_policy
            """,
            (
                recurring_id,
                cron,
                timezone_name,
                misfire_policy,
                queue,
                priority,
                target_node,
                target_tag,
                payload_type,
                payload_json,
                next_trigger_at,
                enabled,
                timeout_seconds,
                overlap_policy,
            ),
        )

    # ---------------------------------------------------------------- worker

    async def pickup(
        self, conn: Conn, job_id: uuid.UUID, node_id: str, lock_seconds: float
    ) -> JobRow | None:
        """Claim a job for this node. ``None`` means someone else owns it — just ack.

        ``AWAITING_RETRY`` is claimable alongside ``ENQUEUED``: a retry was re-published
        through the outbox and is eligible again. Done as a single conditional UPDATE, so
        two nodes racing on the same delivery cannot both win.
        """
        cur = await conn.execute(
            f"""
            UPDATE job SET
                state = 'PROCESSING',
                attempts = attempts + 1,
                version = version + 1,
                locked_by = %s,
                locked_until = now() + make_interval(secs => %s),
                started_at = now(),
                updated_at = now()
            FROM (SELECT state AS prev_state FROM job WHERE id = %s) AS before
            WHERE id = %s
              AND state IN ('ENQUEUED', 'AWAITING_RETRY')
              AND pending_deps = 0
            RETURNING {_JOB_COLUMNS}, before.prev_state
            """,
            (node_id, lock_seconds, job_id, job_id),
        )
        record = await cur.fetchone()
        if record is None:
            return None
        row = _to_row(record)
        # The sub-select reads the pre-UPDATE snapshot, so the timeline shows what the job
        # was picked up *from* — ENQUEUED on a first run, AWAITING_RETRY on a retry.
        await self.insert_event(
            conn,
            job_id=job_id,
            event_type="PICKED_UP",
            prev_state=JobState(record["prev_state"]),
            new_state=JobState.PROCESSING,
            actor=node_id,
        )
        return row

    async def extend_locks(self, conn: Conn, node_id: str, lock_seconds: float) -> int:
        """One bulk lease renewal for everything this node is running. ``version`` is untouched."""
        cur = await conn.execute(
            """
            UPDATE job
            SET locked_until = now() + make_interval(secs => %s), updated_at = now()
            WHERE locked_by = %s AND state = 'PROCESSING'
            """,
            (lock_seconds, node_id),
        )
        return cur.rowcount

    async def finish_terminal(
        self,
        conn: Conn,
        *,
        job_id: uuid.UUID,
        expected_version: int,
        terminal: JobState,
        error_msg: str | None = None,
        error_stack: str | None = None,
        actor: str | None = None,
    ) -> bool:
        """CAS a job into SUCCEEDED / FAILED / CANCELLED, stamping ``duration_ms``."""
        if not terminal.is_terminal:
            raise ValueError(f"{terminal} is not a terminal state")
        cur = await conn.execute("SELECT state FROM job WHERE id = %s", (job_id,))
        snapshot = await cur.fetchone()
        prev_state = JobState(snapshot["state"]) if snapshot else None

        cur = await conn.execute(
            """
            UPDATE job SET
                state = %s,
                version = version + 1,
                locked_by = NULL,
                locked_until = NULL,
                duration_ms = CASE
                    WHEN started_at IS NOT NULL
                    THEN (EXTRACT(EPOCH FROM (now() - started_at)) * 1000)::bigint
                    ELSE duration_ms
                END,
                updated_at = now()
            WHERE id = %s AND version = %s
            """,
            (terminal.value, job_id, expected_version),
        )
        if cur.rowcount != 1:
            return False
        # MANUAL_* prefix when a human triggered it — same convention as the dashboard.
        event_type = f"MANUAL_{terminal.value}" if actor else terminal.value
        await self.insert_event(
            conn,
            job_id=job_id,
            event_type=event_type,
            prev_state=prev_state,
            new_state=terminal,
            actor=actor,
            error_msg=error_msg,
            error_stack=error_stack,
        )
        return True

    async def mark_for_retry(
        self,
        conn: Conn,
        *,
        job_id: uuid.UUID,
        expected_version: int,
        backoff_seconds: float,
        error_msg: str | None = None,
        error_stack: str | None = None,
    ) -> bool:
        """PROCESSING -> AWAITING_RETRY, releasing the lock.

        ``scheduled_at`` is informational; redelivery is driven by the outbox row the caller
        inserts in the same transaction with ``delay_ms = backoff``.
        """
        cur = await conn.execute(
            """
            UPDATE job SET
                state = 'AWAITING_RETRY',
                version = version + 1,
                locked_by = NULL,
                locked_until = NULL,
                scheduled_at = now() + make_interval(secs => %s),
                updated_at = now()
            WHERE id = %s AND version = %s
            """,
            (max(0.0, backoff_seconds), job_id, expected_version),
        )
        if cur.rowcount != 1:
            return False
        await self.insert_event(
            conn,
            job_id=job_id,
            event_type="RETRY",
            prev_state=JobState.PROCESSING,
            new_state=JobState.AWAITING_RETRY,
            error_msg=error_msg,
            error_stack=error_stack,
        )
        return True

    async def release_to_enqueued(
        self, conn: Conn, *, job_id: uuid.UUID, expected_version: int
    ) -> bool:
        """Hand a claimed job back without consuming an attempt.

        Used when the type is paused: the job returns to ENQUEUED and the caller inserts a
        delayed outbox row so it comes back later.
        """
        cur = await conn.execute(
            """
            UPDATE job SET
                state = 'ENQUEUED',
                version = version + 1,
                locked_by = NULL,
                locked_until = NULL,
                attempts = GREATEST(attempts - 1, 0),
                started_at = NULL,
                updated_at = now()
            WHERE id = %s AND version = %s
            """,
            (job_id, expected_version),
        )
        return cur.rowcount == 1

    async def update_progress(
        self,
        conn: Conn,
        *,
        job_id: uuid.UUID,
        progress: float | None,
        message: str | None,
        succeeded: int | None = None,
        failed: int | None = None,
        total: int | None = None,
    ) -> None:
        """Best-effort progress write. No CAS — a lost race just costs one dashboard tick."""
        await conn.execute(
            """
            UPDATE job SET
                progress = %s,
                progress_msg = %s,
                progress_succeeded = %s,
                progress_failed = %s,
                progress_total = %s,
                progress_updated_at = now(),
                updated_at = now()
            WHERE id = %s AND state = 'PROCESSING'
            """,
            (progress, message, succeeded, failed, total, job_id),
        )

    async def is_cancellation_requested(self, conn: Conn, job_id: uuid.UUID) -> bool:
        cur = await conn.execute(
            "SELECT cancel_requested_at IS NOT NULL AS requested FROM job WHERE id = %s",
            (job_id,),
        )
        record = await cur.fetchone()
        return bool(record and record["requested"])

    async def is_type_paused(self, conn: Conn, payload_type: str) -> bool:
        cur = await conn.execute(
            "SELECT 1 FROM job_type_pause WHERE payload_type = %s", (payload_type,)
        )
        return await cur.fetchone() is not None

    async def upsert_worker_heartbeat(
        self,
        conn: Conn,
        *,
        node_id: str,
        host: str,
        tags: Sequence[str],
        started_at: datetime,
        in_flight_by_queue: dict[str, int],
    ) -> None:
        """Publish this node to the dashboard's Workers screen.

        ``started_at`` is preserved on conflict so the dashboard can show real uptime.
        """
        await conn.execute(
            """
            INSERT INTO worker (
                node_id, host, tags, last_heartbeat, started_at, in_flight_count, in_flight_by_queue
            ) VALUES (%s, %s, %s, now(), %s, %s, %s::jsonb)
            ON CONFLICT (node_id) DO UPDATE SET
                host = EXCLUDED.host,
                tags = EXCLUDED.tags,
                last_heartbeat = EXCLUDED.last_heartbeat,
                in_flight_count = EXCLUDED.in_flight_count,
                in_flight_by_queue = EXCLUDED.in_flight_by_queue
            """,
            (
                node_id,
                host,
                list(tags),
                started_at,
                sum(in_flight_by_queue.values()),
                json.dumps(in_flight_by_queue),
            ),
        )

    async def delete_worker(self, conn: Conn, node_id: str) -> None:
        await conn.execute("DELETE FROM worker WHERE node_id = %s", (node_id,))

    # ---------------------------------------------------------------- DAG cascade

    async def resolve_cascade(
        self, conn: Conn, *, root_id: uuid.UUID, root_terminal: JobState
    ) -> list[JobRow]:
        """Walk the dependency graph after a terminal transition.

        A succeeding parent (or an ``IGNORE`` edge) decrements each child's counter and
        promotes it to ENQUEUED at zero. A failing parent cascades FAILED or CANCELLED
        according to the edge's ``on_failure``, then recurses into that child's own children.

        Returns the children promoted to ENQUEUED so the caller can give each an outbox row.
        Iterative BFS — a deep DAG must not blow the stack.
        """
        promoted: list[JobRow] = []
        queue: deque[tuple[uuid.UUID, JobState]] = deque([(root_id, root_terminal)])
        while queue:
            parent_id, parent_state = queue.popleft()
            cur = await conn.execute(
                "SELECT child_id, on_failure FROM job_dependency WHERE parent_id = %s",
                (parent_id,),
            )
            for edge in await cur.fetchall():
                child_id = edge["child_id"]
                on_failure = OnFailure(edge["on_failure"])
                if parent_state is JobState.SUCCEEDED or on_failure is OnFailure.IGNORE:
                    child = await self._decrement_pending_deps(conn, child_id)
                    if child is not None:
                        promoted.append(child)
                else:
                    terminal = (
                        JobState.FAILED
                        if on_failure is OnFailure.PROPAGATE_FAILURE
                        else JobState.CANCELLED
                    )
                    if await self._cascade_terminal_if_awaiting(conn, child_id, terminal):
                        queue.append((child_id, terminal))
        return promoted

    async def _decrement_pending_deps(
        self, conn: Conn, child_id: uuid.UUID
    ) -> JobRow | None:
        """Tick one dependency off a child; return the row if that promoted it to ENQUEUED.

        ``SELECT ... FOR UPDATE`` rather than a version CAS: several parents finishing in the
        same instant all decrement this child, and under a CAS the losers' decrements would
        be dropped, stranding the child in AWAITING_DEPS forever. The row lock serialises them.
        """
        cur = await conn.execute(
            "SELECT state, pending_deps, initial_pending_deps FROM job WHERE id = %s FOR UPDATE",
            (child_id,),
        )
        current = await cur.fetchone()
        if current is None or current["state"] != JobState.AWAITING_DEPS.value:
            return None

        remaining = max(0, current["pending_deps"] - 1)
        promote = remaining == 0
        initial = current["initial_pending_deps"]
        # While parked, expose "N of M dependencies satisfied" as a progress fraction.
        derived = None if promote or initial <= 0 else min(1.0, (initial - remaining) / initial)
        # Decide in Python: a bare NULL parameter inside `IS NOT NULL` gives Postgres nothing
        # to infer a type from ("could not determine data type of parameter").
        touch_progress = promote or derived is not None

        await conn.execute(
            """
            UPDATE job SET
                pending_deps = %s,
                state = CASE WHEN %s THEN 'ENQUEUED' ELSE state END,
                version = version + 1,
                progress = %s,
                progress_updated_at = CASE
                    WHEN %s THEN now() ELSE progress_updated_at
                END,
                updated_at = now()
            WHERE id = %s
            """,
            (remaining, promote, derived, touch_progress, child_id),
        )
        if not promote:
            return None
        await self.insert_event(
            conn,
            job_id=child_id,
            event_type="PROMOTED",
            prev_state=JobState.AWAITING_DEPS,
            new_state=JobState.ENQUEUED,
        )
        return await self.find_by_id(conn, child_id)

    async def _cascade_terminal_if_awaiting(
        self, conn: Conn, child_id: uuid.UUID, terminal: JobState
    ) -> bool:
        """Fail or cancel a child that is still parked behind its parents."""
        cur = await conn.execute(
            """
            UPDATE job SET
                state = %s,
                version = version + 1,
                locked_by = NULL,
                locked_until = NULL,
                updated_at = now()
            WHERE id = %s AND state = 'AWAITING_DEPS'
            """,
            (terminal.value, child_id),
        )
        if cur.rowcount != 1:
            return False
        await self.insert_event(
            conn,
            job_id=child_id,
            event_type="CASCADE_CANCELLED"
            if terminal is JobState.CANCELLED
            else "CASCADED_FAILURE",
            prev_state=JobState.AWAITING_DEPS,
            new_state=terminal,
        )
        return True

    # ---------------------------------------------------------------- operator actions

    async def request_cancel(
        self, conn: Conn, job_id: uuid.UUID, by: str | None
    ) -> str:
        """Cancel a job.

        Returns ``CANCELLED``, ``CANCEL_REQUESTED``, ``ALREADY_TERMINAL`` or ``NOT_FOUND``.

        A PROCESSING job cannot be stopped from the outside — it is flagged and notified, and
        its handler decides when to stop (``ctx.is_cancellation_requested()``).
        """
        cur = await conn.execute("SELECT state, version FROM job WHERE id = %s", (job_id,))
        record = await cur.fetchone()
        if record is None:
            return "NOT_FOUND"
        state = JobState(record["state"])
        if state.is_terminal:
            return "ALREADY_TERMINAL"
        if state is JobState.PROCESSING:
            await conn.execute(
                """
                UPDATE job
                SET cancel_requested_at = now(), cancel_requested_by = %s, updated_at = now()
                WHERE id = %s AND cancel_requested_at IS NULL
                """,
                (by, job_id),
            )
            await self.insert_event(
                conn, job_id=job_id, event_type="CANCEL_REQUESTED", actor=by
            )
            # Push the signal to whichever node is running it.
            await conn.execute("SELECT pg_notify('job_cancel', %s)", (str(job_id),))
            return "CANCEL_REQUESTED"
        ok = await self.finish_terminal(
            conn,
            job_id=job_id,
            expected_version=record["version"],
            terminal=JobState.CANCELLED,
            actor=by,
        )
        return "CANCELLED" if ok else "CONFLICT"

    async def manual_retry(
        self, conn: Conn, job_id: uuid.UUID, by: str | None, fresh_budget: bool
    ) -> tuple[str, JobRow | None]:
        """Revive a FAILED job. ``fresh_budget`` resets attempts; otherwise grants one more."""
        cur = await conn.execute("SELECT state, version FROM job WHERE id = %s", (job_id,))
        record = await cur.fetchone()
        if record is None:
            return "NOT_FOUND", None
        if JobState(record["state"]) is not JobState.FAILED:
            return "NOT_FAILED", None
        cur = await conn.execute(
            f"""
            UPDATE job SET
                state = 'ENQUEUED',
                version = version + 1,
                attempts = CASE WHEN %s THEN 0 ELSE attempts END,
                max_attempts = CASE WHEN %s THEN max_attempts ELSE attempts + 1 END,
                locked_by = NULL,
                locked_until = NULL,
                scheduled_at = NULL,
                started_at = NULL,
                updated_at = now()
            WHERE id = %s AND version = %s
            RETURNING {_JOB_COLUMNS}
            """,
            (fresh_budget, fresh_budget, job_id, record["version"]),
        )
        updated = await cur.fetchone()
        if updated is None:
            return "CONFLICT", None
        await self.insert_event(
            conn,
            job_id=job_id,
            event_type="MANUAL_RETRY" if fresh_budget else "MANUAL_RETRY_ONCE",
            prev_state=JobState.FAILED,
            new_state=JobState.ENQUEUED,
            actor=by,
        )
        return "RETRIED", _to_row(updated)

    async def delete_job(self, conn: Conn, job_id: uuid.UUID) -> str:
        """Delete a terminal job. Events, dependencies and outbox rows cascade with it."""
        cur = await conn.execute("SELECT state FROM job WHERE id = %s", (job_id,))
        record = await cur.fetchone()
        if record is None:
            return "NOT_FOUND"
        if not JobState(record["state"]).is_terminal:
            return "NOT_TERMINAL"
        await conn.execute("DELETE FROM job WHERE id = %s", (job_id,))
        return "DELETED"

    # ---------------------------------------------------------------- events

    async def notify_event(self, conn: Conn, payload: str) -> None:
        """Broadcast a dashboard event over the ``scheduler_events`` LISTEN/NOTIFY channel."""
        try:
            await conn.execute("SELECT pg_notify('scheduler_events', %s)", (payload,))
        except psycopg.Error:  # observability only — never fail a job over it
            log.debug("scheduler_events NOTIFY failed", exc_info=True)
