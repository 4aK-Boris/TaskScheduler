"""The producer side: putting work into the scheduler.

Every entry point writes the ``job`` row and its ``outbox`` row in **one transaction**. The
job therefore becomes visible to workers only if the surrounding business transaction
commits, and a worker can never receive a delivery for a row that does not exist yet. The
actual RabbitMQ publish is done by the Kotlin infra leader polling the outbox — this client
never publishes directly, so there is exactly one writer to the broker.
"""

from __future__ import annotations

import logging
import uuid
from dataclasses import dataclass
from datetime import datetime, timedelta, timezone
from typing import Any

import psycopg

from .config import SchedulerConfig
from .cron import next_trigger_at
from .errors import ConfigurationError
from .events import EventEncoder
from .job import HandlerRegistry
from .models import (
    MAX_PRIORITY,
    MIN_PRIORITY,
    ConcurrencyPolicy,
    JobState,
    MisfirePolicy,
    OnFailure,
    RecurringOverlap,
)
from .serde import encode_payload, payload_type_of
from .storage import Conn, Storage

log = logging.getLogger(__name__)

__all__ = ["Scheduler"]

_PG_UNIQUE_VIOLATION = "23505"
_ENQUEUE_ONCE_ATTEMPTS = 5


@dataclass(slots=True)
class _EnqueueParams:
    """Everything an enqueue needs, resolved once before the transaction opens."""

    job_id: uuid.UUID
    queue: str
    priority: int
    max_attempts: int
    timeout_seconds: int | None
    payload_type: str
    payload_json: str
    routing_key: str
    target_node: str | None
    target_tag: str | None


class Scheduler:
    """Enqueue jobs and manage their lifecycle.

    Safe to share across your whole application — it is stateless beyond the connection
    pool. Use it as an async context manager, or call :meth:`start` / :meth:`close`.
    """

    def __init__(
        self,
        config: SchedulerConfig,
        *,
        registry: HandlerRegistry | None = None,
        storage: Storage | None = None,
    ) -> None:
        self._config = config
        self._registry = registry
        self._storage = storage or Storage(config)
        self._owns_storage = storage is None
        self._events = EventEncoder(config.node_id)

    @property
    def storage(self) -> Storage:
        return self._storage

    @property
    def config(self) -> SchedulerConfig:
        return self._config

    async def start(self) -> None:
        await self._storage.start()

    async def close(self) -> None:
        if self._owns_storage:
            await self._storage.close()

    async def __aenter__(self) -> Scheduler:
        await self.start()
        return self

    async def __aexit__(self, *exc: object) -> None:
        await self.close()

    # ------------------------------------------------------------------ enqueue

    async def enqueue(
        self,
        payload: Any,
        *,
        queue: str | None = None,
        priority: int | None = None,
        max_attempts: int | None = None,
        timeout_seconds: int | None = None,
        target_node: str | None = None,
        target_tag: str | None = None,
    ) -> uuid.UUID:
        """Run ``payload`` as soon as a worker is free. Returns the new job id."""
        params = self._build(
            payload,
            queue=queue,
            priority=priority,
            max_attempts=max_attempts,
            timeout_seconds=timeout_seconds,
            target_node=target_node,
            target_tag=target_tag,
        )
        async with self._storage.transaction() as conn:
            await self._insert(conn, params, state=JobState.ENQUEUED)
            await self._storage.insert_outbox(
                conn,
                job_id=params.job_id,
                routing_key=params.routing_key,
                priority=params.priority,
                delay_ms=0,
            )
        await self._emit_created(params)
        return params.job_id

    async def schedule_at(
        self,
        payload: Any,
        at: datetime,
        *,
        queue: str | None = None,
        priority: int | None = None,
        max_attempts: int | None = None,
        timeout_seconds: int | None = None,
        target_node: str | None = None,
        target_tag: str | None = None,
    ) -> uuid.UUID:
        """Run ``payload`` at a specific moment.

        Within the 24-hour fast-forward window the job is handed to the broker's delayed
        exchange straight away, which gives it millisecond accuracy. Further out it is parked
        as SCHEDULED with no outbox row, and infra promotes it once it comes within the
        window — RabbitMQ is not a place to keep a million long-delay messages.
        """
        if at.tzinfo is None:
            at = at.replace(tzinfo=timezone.utc)
        delay = at - datetime.now(timezone.utc)
        delay_seconds = max(0.0, delay.total_seconds())
        within_window = delay_seconds <= self._config.fast_forward_window_seconds

        params = self._build(
            payload,
            queue=queue,
            priority=priority,
            max_attempts=max_attempts,
            timeout_seconds=timeout_seconds,
            target_node=target_node,
            target_tag=target_tag,
        )
        state = JobState.ENQUEUED if within_window else JobState.SCHEDULED
        async with self._storage.transaction() as conn:
            await self._insert(conn, params, state=state, scheduled_at=at)
            if within_window:
                await self._storage.insert_outbox(
                    conn,
                    job_id=params.job_id,
                    routing_key=params.routing_key,
                    priority=params.priority,
                    delay_ms=int(delay_seconds * 1000),
                )
        await self._emit_created(params)
        return params.job_id

    async def schedule_in(self, payload: Any, delay: timedelta, **options: Any) -> uuid.UUID:
        """Convenience wrapper over :meth:`schedule_at` for a relative delay."""
        return await self.schedule_at(payload, datetime.now(timezone.utc) + delay, **options)

    async def enqueue_once(
        self,
        key: str,
        payload: Any,
        *,
        policy: ConcurrencyPolicy = ConcurrencyPolicy.SKIP,
        **options: Any,
    ) -> uuid.UUID:
        """Enqueue at most one active job per ``key``.

        * ``SKIP`` (default) — coalesce: if the key is already active, return that job's id
          and write nothing.
        * ``REPLACE`` — cancel the incumbent and run this one instead.
        * ``ENQUEUE_AFTER`` — park this one behind the incumbent so they never overlap.

        Returns the id of the job that owns ``key`` going forward. Two unique partial indexes
        enforce the slots in the database, so concurrent producers cannot both win.
        """
        if policy is ConcurrencyPolicy.SKIP:
            async with self._storage.connection() as conn:
                existing = await self._storage.find_active_by_idempotency_key(conn, key)
                if existing is not None:
                    return existing.id

        last_error: Exception | None = None
        for _ in range(_ENQUEUE_ONCE_ATTEMPTS):
            params = self._build(payload, **options)
            try:
                resolved = await self._resolve_once(key, params, policy)
            except psycopg.errors.UniqueViolation as exc:
                # Lost the create race to another producer — re-read and try again.
                last_error = exc
                continue
            except psycopg.Error as exc:
                if getattr(exc, "sqlstate", None) != _PG_UNIQUE_VIOLATION:
                    raise
                last_error = exc
                continue
            if resolved == params.job_id:
                await self._emit_created(params)
            return resolved
        raise RuntimeError(
            f"enqueue_once({key!r}) lost {_ENQUEUE_ONCE_ATTEMPTS} races against concurrent "
            f"producers — the key is contended far beyond what retrying can absorb"
        ) from last_error

    async def _resolve_once(
        self, key: str, params: _EnqueueParams, policy: ConcurrencyPolicy
    ) -> uuid.UUID:
        async with self._storage.transaction() as conn:
            leader = await self._storage.find_active_by_idempotency_key(conn, key)
            if leader is None:
                await self._insert(conn, params, state=JobState.ENQUEUED, idempotency_key=key)
                await self._storage.insert_outbox(
                    conn,
                    job_id=params.job_id,
                    routing_key=params.routing_key,
                    priority=params.priority,
                    delay_ms=0,
                )
                return params.job_id

            if policy is ConcurrencyPolicy.SKIP:
                return leader.id

            if policy is ConcurrencyPolicy.REPLACE:
                await self._storage.request_cancel(conn, leader.id, by=self._config.node_id)
                # The incumbent may be PROCESSING and stop only cooperatively, so the
                # replacement waits behind it rather than running alongside.
                await self._insert(
                    conn,
                    params,
                    state=JobState.AWAITING_DEPS,
                    idempotency_key=key,
                    pending_deps=1,
                )
                await self._storage.insert_dependency(
                    conn,
                    parent_id=leader.id,
                    child_id=params.job_id,
                    on_failure=OnFailure.IGNORE,
                )
                return params.job_id

            # ENQUEUE_AFTER — park a successor behind the leader. No outbox row yet; the
            # IGNORE edge promotes it when the leader terminates, whatever the outcome.
            await self._insert(
                conn, params, state=JobState.AWAITING_DEPS, idempotency_key=key, pending_deps=1
            )
            await self._storage.insert_dependency(
                conn,
                parent_id=leader.id,
                child_id=params.job_id,
                on_failure=OnFailure.IGNORE,
            )
            return params.job_id

    async def enqueue_after(
        self,
        payload: Any,
        wait_for: list[uuid.UUID],
        *,
        on_parent_failure: OnFailure = OnFailure.PROPAGATE_FAILURE,
        **options: Any,
    ) -> uuid.UUID:
        """Run ``payload`` once every job in ``wait_for`` has succeeded (a barrier).

        Parents that have already finished are resolved at insert time, so a race between
        registering the barrier and the last parent completing cannot strand the child.
        """
        parents = list(dict.fromkeys(wait_for))
        if not parents:
            return await self.enqueue(payload, **options)

        params = self._build(payload, **options)
        async with self._storage.transaction() as conn:
            cur = await conn.execute(
                "SELECT id, state FROM job WHERE id = ANY(%s) FOR UPDATE", (parents,)
            )
            found = {r["id"]: JobState(r["state"]) for r in await cur.fetchall()}
            missing = [p for p in parents if p not in found]
            if missing:
                raise ConfigurationError(
                    f"cannot wait for unknown job(s): {', '.join(str(m) for m in missing)}"
                )

            pending = [p for p, s in found.items() if not s.is_terminal]
            failed = [
                p
                for p, s in found.items()
                if s in (JobState.FAILED, JobState.CANCELLED)
            ]

            if failed and on_parent_failure is not OnFailure.IGNORE:
                terminal = (
                    JobState.FAILED
                    if on_parent_failure is OnFailure.PROPAGATE_FAILURE
                    else JobState.CANCELLED
                )
                await self._insert(conn, params, state=terminal)
                await self._storage.insert_event(
                    conn,
                    job_id=params.job_id,
                    event_type="CASCADED_FAILURE"
                    if terminal is JobState.FAILED
                    else "CASCADE_CANCELLED",
                    new_state=terminal,
                    error_msg="parent job had already failed when this barrier was registered",
                )
                return params.job_id

            state = JobState.AWAITING_DEPS if pending else JobState.ENQUEUED
            await self._insert(conn, params, state=state, pending_deps=len(pending))
            for parent in parents:
                await self._storage.insert_dependency(
                    conn,
                    parent_id=parent,
                    child_id=params.job_id,
                    on_failure=on_parent_failure,
                )
            if not pending:
                await self._storage.insert_outbox(
                    conn,
                    job_id=params.job_id,
                    routing_key=params.routing_key,
                    priority=params.priority,
                    delay_ms=0,
                )
        await self._emit_created(params)
        return params.job_id

    async def chain(self, *payloads: Any, **options: Any) -> list[uuid.UUID]:
        """Run payloads strictly in order, each waiting on the previous one to succeed.

        A failure anywhere fails the rest of the chain (``PROPAGATE_FAILURE``).
        """
        if not payloads:
            return []
        ids = [await self.enqueue(payloads[0], **options)]
        for payload in payloads[1:]:
            ids.append(await self.enqueue_after(payload, [ids[-1]], **options))
        return ids

    # ------------------------------------------------------------------ recurring

    async def recurring(
        self,
        recurring_id: str,
        cron: str,
        payload: Any,
        *,
        timezone_name: str | None = None,
        queue: str | None = None,
        priority: int = 0,
        misfire_policy: MisfirePolicy = MisfirePolicy.CATCH_UP_ONE,
        overlap: RecurringOverlap = RecurringOverlap.ALLOW,
        timeout_seconds: int | None = None,
        target_node: str | None = None,
        target_tag: str | None = None,
        enabled: bool = True,
    ) -> None:
        """Register a cron definition. Idempotent — calling again with the same id updates it.

        Firing is done by the Kotlin infra leader, so a definition survives this process
        restarting and never double-fires across replicas. Register at startup, like a route.

        Cron is 5 fields (``m h dom mon dow``) or 6 with leading seconds.
        """
        first = next_trigger_at(cron, timezone_name=timezone_name)
        async with self._storage.transaction() as conn:
            await self._storage.upsert_recurring(
                conn,
                recurring_id=recurring_id,
                cron=cron,
                payload_type=payload_type_of(type(payload)),
                payload_json=encode_payload(payload),
                queue=queue or self._config.default_queue,
                next_trigger_at=first,
                timezone_name=timezone_name,
                misfire_policy=misfire_policy.value,
                overlap_policy=overlap.value,
                priority=self._clamp_priority(priority),
                target_node=target_node,
                target_tag=target_tag,
                timeout_seconds=timeout_seconds,
                enabled=enabled,
            )
        log.info("registered recurring job %s (%s), next run %s", recurring_id, cron, first)

    # ------------------------------------------------------------------ operator actions

    async def cancel(self, job_id: uuid.UUID, by: str | None = None) -> str:
        """Cancel a job.

        A job that has not started yet flips to CANCELLED immediately. A running one is
        flagged and signalled — it stops when its handler next checks
        ``ctx.is_cancellation_requested()``, so cooperative handlers stop promptly and
        others finish their current work.

        Returns ``CANCELLED``, ``CANCEL_REQUESTED``, ``ALREADY_TERMINAL`` or ``NOT_FOUND``.
        """
        async with self._storage.transaction() as conn:
            return await self._storage.request_cancel(conn, job_id, by)

    async def retry(
        self, job_id: uuid.UUID, by: str | None = None, *, fresh_budget: bool = True
    ) -> str:
        """Re-run a FAILED job. ``fresh_budget`` resets the attempt counter; otherwise +1.

        Returns ``RETRIED``, ``NOT_FAILED``, ``NOT_FOUND`` or ``CONFLICT``. DAG dependents
        that already cascaded to FAILED are not revived — retry each branch you want re-run.
        """
        async with self._storage.transaction() as conn:
            result, row = await self._storage.manual_retry(conn, job_id, by, fresh_budget)
            if result == "RETRIED" and row is not None:
                await self._storage.insert_outbox(
                    conn,
                    job_id=row.id,
                    routing_key=row.routing_key,
                    priority=row.priority,
                    delay_ms=0,
                )
            return result

    async def delete(self, job_id: uuid.UUID, by: str | None = None) -> str:
        """Delete a terminal job and its history.

        Returns ``DELETED``, ``NOT_TERMINAL`` or ``NOT_FOUND``.
        """
        async with self._storage.transaction() as conn:
            return await self._storage.delete_job(conn, job_id)

    # ------------------------------------------------------------------ internals

    def _build(
        self,
        payload: Any,
        *,
        queue: str | None = None,
        priority: int | None = None,
        max_attempts: int | None = None,
        timeout_seconds: int | None = None,
        target_node: str | None = None,
        target_tag: str | None = None,
    ) -> _EnqueueParams:
        payload_type = payload_type_of(type(payload))
        entry = self._registry.find(payload_type) if self._registry else None

        resolved_queue = queue or self._config.default_queue
        resolved_priority = priority
        if resolved_priority is None:
            resolved_priority = entry.default_priority if entry else 0
        resolved_max_attempts = max_attempts
        if resolved_max_attempts is None:
            resolved_max_attempts = (
                entry.max_attempts
                if entry and entry.max_attempts is not None
                else self._config.default_max_attempts
            )
        resolved_timeout = timeout_seconds
        if resolved_timeout is None and entry is not None:
            resolved_timeout = entry.timeout_seconds

        # node > tag > queue, matching the Kotlin routing key. Getting this wrong strands
        # jobs on a queue no worker consumes.
        if target_node is not None:
            routing_key = f"node.{target_node}"
        elif target_tag is not None:
            routing_key = f"tag.{target_tag}"
        else:
            routing_key = resolved_queue

        return _EnqueueParams(
            job_id=uuid.uuid4(),
            queue=resolved_queue,
            priority=self._clamp_priority(resolved_priority),
            max_attempts=max(1, resolved_max_attempts),
            timeout_seconds=resolved_timeout,
            payload_type=payload_type,
            payload_json=encode_payload(payload),
            routing_key=routing_key,
            target_node=target_node,
            target_tag=target_tag,
        )

    async def _insert(
        self,
        conn: Conn,
        params: _EnqueueParams,
        *,
        state: JobState,
        scheduled_at: datetime | None = None,
        pending_deps: int = 0,
        idempotency_key: str | None = None,
    ) -> None:
        await self._storage.insert_job(
            conn,
            job_id=params.job_id,
            state=state,
            queue=params.queue,
            priority=params.priority,
            payload_type=params.payload_type,
            payload_json=params.payload_json,
            max_attempts=params.max_attempts,
            timeout_seconds=params.timeout_seconds,
            scheduled_at=scheduled_at,
            pending_deps=pending_deps,
            idempotency_key=idempotency_key,
            target_node=params.target_node,
            target_tag=params.target_tag,
        )
        await self._storage.insert_event(
            conn, job_id=params.job_id, event_type="CREATED", new_state=state
        )

    async def _emit_created(self, params: _EnqueueParams) -> None:
        if not self._config.emit_events:
            return
        payload = self._events.job_created(
            params.job_id, params.queue, params.payload_type
        )
        if payload is None:
            return
        async with self._storage.connection() as conn:
            await self._storage.notify_event(conn, payload)

    @staticmethod
    def _clamp_priority(priority: int) -> int:
        return max(MIN_PRIORITY, min(MAX_PRIORITY, priority))
