"""The consumer side: claiming jobs and running handlers.

One delivery goes through a fixed sequence — claim, screen, decode, execute, finalise:

1. **Claim.** A conditional UPDATE flips the row to PROCESSING only if it is still
   claimable. Losing that race is normal (a lease expired and infra republished, two
   deliveries for one row) and simply means acking without work.
2. **Screen.** A paused type is handed back unrun; an unknown type or an undecodable
   payload fails terminally, because neither will fix itself on a retry.
3. **Execute** under the job's timeout, with the lease renewed in the background.
4. **Finalise.** The terminal transition and the DAG cascade it unblocks happen in one
   transaction, so a child is never promoted without its parent being recorded as done.

Nothing here trusts the broker for correctness: acks are bookkeeping, and every decision is
a CAS against Postgres.
"""

from __future__ import annotations

import asyncio
import contextlib
import logging
import socket
import traceback
import uuid
from dataclasses import dataclass, field
from datetime import datetime, timezone
from typing import Any

from .config import RabbitConfig, SchedulerConfig, WorkerConfig
from .context import JobContext
from .errors import (
    HandlerNotRegisteredError,
    JobCancellationError,
    NonRetriableError,
    PayloadDecodeError,
)
from .events import EventEncoder, JobCancelListener
from .job import HandlerEntry, HandlerRegistry
from .models import JobRow, JobState
from .retry import RetryPolicy
from .serde import decode_payload
from .storage import MAX_ERROR_MSG_LEN, MAX_ERROR_STACK_LEN, Storage
from .transport import RabbitTransport

log = logging.getLogger(__name__)

__all__ = ["WorkerPool"]


@dataclass(slots=True)
class _Active:
    """A job running on this node right now."""

    task: asyncio.Task[None]
    context: JobContext
    row: JobRow
    force_cancel_handle: asyncio.TimerHandle | None = None


@dataclass(slots=True)
class _InFlight:
    """Per-queue counters published to the dashboard's Workers screen."""

    counts: dict[str, int] = field(default_factory=dict)

    def increment(self, queue: str) -> None:
        self.counts[queue] = self.counts.get(queue, 0) + 1

    def decrement(self, queue: str) -> None:
        remaining = self.counts.get(queue, 0) - 1
        if remaining > 0:
            self.counts[queue] = remaining
        else:
            self.counts.pop(queue, None)

    def snapshot(self) -> dict[str, int]:
        return dict(self.counts)


class WorkerPool:
    """Consumes queues and runs handlers. Start it once at boot, stop it on shutdown."""

    def __init__(
        self,
        *,
        scheduler_config: SchedulerConfig,
        worker_config: WorkerConfig,
        rabbit_config: RabbitConfig,
        registry: HandlerRegistry,
        storage: Storage | None = None,
        transport: RabbitTransport | None = None,
    ) -> None:
        worker_config.validate()
        if len(registry) == 0:
            raise ValueError(
                "no handlers registered — a worker with an empty registry would fail every "
                "job it picks up"
            )
        self._scheduler_config = scheduler_config
        self._config = worker_config
        self._registry = registry
        self._storage = storage or Storage(scheduler_config)
        self._owns_storage = storage is None
        self._transport = transport or RabbitTransport(rabbit_config)
        self._owns_transport = transport is None
        self._events = EventEncoder(worker_config.node_id)
        self._cancel_listener = JobCancelListener(scheduler_config.dsn, self._on_cancel_signal)

        self._active: dict[uuid.UUID, _Active] = {}
        self._in_flight = _InFlight()
        self._semaphores: dict[str, asyncio.Semaphore] = {}
        self._loops: list[asyncio.Task[None]] = []
        self._started_at = datetime.now(timezone.utc)
        self._host = self._resolve_host()
        self._running = False

    # ------------------------------------------------------------------ lifecycle

    async def start(self) -> None:
        if self._running:
            return
        await self._storage.start()
        await self._transport.start()

        for queue in self._config.queues:
            self._semaphores[queue.name] = asyncio.Semaphore(queue.concurrency)

        self._running = True
        self._loops.append(asyncio.create_task(self._heartbeat_loop(), name="ts-heartbeat"))
        self._loops.append(asyncio.create_task(self._registry_loop(), name="ts-worker-registry"))
        self._cancel_listener.start()

        for queue in self._config.queues:
            assert queue.prefetch is not None  # QueueConfig fills it in
            await self._transport.consume(
                queue.name,
                queue.prefetch,
                self._make_delivery_handler(queue.name),
            )

        await self._announce(joined=True)
        log.info(
            "worker %s started — queues=%s, handlers=%s",
            self._config.node_id,
            [q.name for q in self._config.queues],
            self._registry.known_types,
        )

    async def stop(self) -> None:
        """Stop taking new work, let in-flight jobs finish, then release everything.

        Jobs still running when the grace period expires are cancelled; their leases lapse
        and infra's orphan recovery re-enqueues them, so nothing is lost — it just runs twice.
        """
        if not self._running:
            return
        self._running = False

        # Stop deliveries first, but keep the connection: in-flight jobs are still going.
        await self._transport.stop_consumers()

        if self._active:
            log.info("waiting for %d in-flight job(s) to finish", len(self._active))
            pending = [a.task for a in self._active.values()]
            done, still_running = await asyncio.wait(
                pending, timeout=self._config.shutdown_timeout_seconds
            )
            for task in still_running:
                log.warning("job did not finish within the shutdown grace period — cancelling")
                task.cancel()
            if still_running:
                await asyncio.wait(still_running, timeout=5.0)

        await self._cancel_listener.stop()
        for task in self._loops:
            task.cancel()
        for task in self._loops:
            with contextlib.suppress(asyncio.CancelledError):
                await task
        self._loops.clear()

        await self._announce(joined=False)
        with contextlib.suppress(Exception):
            async with self._storage.connection() as conn:
                await self._storage.delete_worker(conn, self._config.node_id)

        if self._owns_transport:
            await self._transport.close()
        if self._owns_storage:
            await self._storage.close()
        log.info("worker %s stopped", self._config.node_id)

    async def __aenter__(self) -> WorkerPool:
        await self.start()
        return self

    async def __aexit__(self, *exc: object) -> None:
        await self.stop()

    # ------------------------------------------------------------------ delivery

    def _make_delivery_handler(self, queue_name: str) -> Any:
        async def handle(job_id: uuid.UUID) -> None:
            semaphore = self._semaphores[queue_name]
            async with semaphore:
                await self._process_one(job_id, queue_name)

        return handle

    async def _process_one(self, job_id: uuid.UUID, queue_name: str) -> None:
        if not self._running:
            return
        async with self._storage.transaction() as conn:
            row = await self._storage.pickup(
                conn, job_id, self._config.node_id, self._config.lock_duration_seconds
            )
        if row is None:
            # Already claimed, cancelled or finished elsewhere — nothing to do.
            log.debug("job %s was not claimable — acking", job_id)
            return

        await self._emit_state(row, JobState.ENQUEUED, JobState.PROCESSING)

        # Paused types go back to the queue unrun, without burning an attempt.
        async with self._storage.connection() as conn:
            paused = await self._storage.is_type_paused(conn, row.payload_type)
        if paused:
            await self._defer_paused(row)
            return

        entry = self._registry.find(row.payload_type)
        if entry is None:
            await self._fail_terminally(
                row,
                HandlerNotRegisteredError(
                    f"no handler registered for payload_type={row.payload_type} "
                    f"(known: {', '.join(self._registry.known_types) or 'none'})"
                ),
            )
            return

        try:
            payload: Any = decode_payload(entry.payload_cls, row.payload_json)
        except PayloadDecodeError as exc:
            await self._fail_terminally(row, exc)
            return

        # Someone cancelled it between enqueue and now — don't start the handler at all.
        async with self._storage.connection() as conn:
            if await self._storage.is_cancellation_requested(conn, row.id):
                await self._finalize(row, JobState.CANCELLED, error_msg="cancelled before start")
                return

        await self._run_handler(row, entry, payload, queue_name)

    async def _run_handler(
        self, row: JobRow, entry: HandlerEntry, payload: Any, queue_name: str
    ) -> None:
        ctx = JobContext(row, self)
        timeout = float(row.timeout_seconds or self._scheduler_config.default_timeout_seconds)
        task = asyncio.create_task(
            asyncio.wait_for(entry.execute(ctx, payload), timeout),
            name=f"ts-job-{row.id}",
        )
        self._active[row.id] = _Active(task=task, context=ctx, row=row)
        self._in_flight.increment(queue_name)
        try:
            await task
        except asyncio.TimeoutError:
            await self._handle_failure(
                row,
                entry,
                payload,
                TimeoutError(f"job exceeded its {timeout:g}s timeout"),
            )
        except JobCancellationError as exc:
            await self._finalize(
                row, JobState.CANCELLED, error_msg=str(exc) or "cancelled by handler"
            )
        except asyncio.CancelledError:
            # Cancelled from the outside — either an operator cancel or shutdown.
            await self._finalize(row, JobState.CANCELLED, error_msg="cancelled in-flight")
            if not self._running:
                raise
        except Exception as exc:
            await self._handle_failure(row, entry, payload, exc)
        else:
            await self._finalize(row, JobState.SUCCEEDED)
        finally:
            active = self._active.pop(row.id, None)
            if active is not None and active.force_cancel_handle is not None:
                active.force_cancel_handle.cancel()
            self._in_flight.decrement(queue_name)

    async def _handle_failure(
        self, row: JobRow, entry: HandlerEntry, payload: Any, error: BaseException
    ) -> None:
        error_msg = f"{type(error).__name__}: {error}"[:MAX_ERROR_MSG_LEN]
        error_stack = "".join(
            traceback.format_exception(type(error), error, error.__traceback__)
        )[:MAX_ERROR_STACK_LEN]

        policy: RetryPolicy | None = (
            entry.retry_policy or self._scheduler_config.default_retry_policy
        )
        terminal = isinstance(error, (NonRetriableError, PayloadDecodeError))
        retries_left = policy is not None and row.attempts < row.max_attempts

        if terminal or not retries_left:
            reason = "non-retriable" if terminal else "attempt budget exhausted"
            log.warning(
                "job %s (%s) failed permanently — %s: %s",
                row.id, row.payload_type, reason, error_msg,
            )
            await self._finalize(
                row, JobState.FAILED, error_msg=error_msg, error_stack=error_stack
            )
            if entry.on_final_failure is not None:
                ctx = JobContext(row, self)
                try:
                    await entry.on_final_failure(ctx, payload, error)
                except Exception:  # a cleanup hook must never take the consumer down
                    log.exception("on_final_failure hook raised for job %s", row.id)
            return

        assert policy is not None
        backoff = policy.next_backoff(row.attempts)
        log.info(
            "job %s (%s) failed on attempt %d/%d — retrying in %.1fs: %s",
            row.id, row.payload_type, row.attempts, row.max_attempts, backoff, error_msg,
        )
        async with self._storage.transaction() as conn:
            updated = await self._storage.mark_for_retry(
                conn,
                job_id=row.id,
                expected_version=row.version,
                backoff_seconds=backoff,
                error_msg=error_msg,
                error_stack=error_stack,
            )
            if not updated:
                log.warning("retry for job %s lost the version race — another node owns it", row.id)
                return
            await self._storage.insert_outbox(
                conn,
                job_id=row.id,
                routing_key=row.routing_key,
                priority=row.priority,
                delay_ms=int(backoff * 1000),
            )
        await self._emit_state(row, JobState.PROCESSING, JobState.AWAITING_RETRY)

    async def _finalize(
        self,
        row: JobRow,
        terminal: JobState,
        *,
        error_msg: str | None = None,
        error_stack: str | None = None,
    ) -> None:
        """Terminal transition plus the DAG cascade it unblocks, in one transaction."""
        async with self._storage.transaction() as conn:
            marked = await self._storage.finish_terminal(
                conn,
                job_id=row.id,
                expected_version=row.version,
                terminal=terminal,
                error_msg=error_msg,
                error_stack=error_stack,
            )
            if not marked:
                log.warning(
                    "could not finalise job %s as %s — another writer moved it first",
                    row.id, terminal.value,
                )
                return
            promoted = await self._storage.resolve_cascade(
                conn, root_id=row.id, root_terminal=terminal
            )
            for child in promoted:
                await self._storage.insert_outbox(
                    conn,
                    job_id=child.id,
                    routing_key=child.routing_key,
                    priority=child.priority,
                    delay_ms=0,
                )
        await self._emit_state(row, JobState.PROCESSING, terminal)

    async def _fail_terminally(self, row: JobRow, error: Exception) -> None:
        """Fail without consulting the retry policy — the cause cannot change between attempts."""
        log.error("job %s: %s", row.id, error)
        await self._finalize(row, JobState.FAILED, error_msg=str(error)[:MAX_ERROR_MSG_LEN])

    async def _defer_paused(self, row: JobRow) -> None:
        """Return a paused-type job to the queue, to come back after the pause delay."""
        delay = self._config.pause_redeliver_delay_seconds
        async with self._storage.transaction() as conn:
            released = await self._storage.release_to_enqueued(
                conn, job_id=row.id, expected_version=row.version
            )
            if not released:
                return
            await self._storage.insert_outbox(
                conn,
                job_id=row.id,
                routing_key=row.routing_key,
                priority=row.priority,
                delay_ms=int(delay * 1000),
            )
        log.info(
            "type %s is paused — job %s returns to %s in %.0fs",
            row.payload_type, row.id, row.queue, delay,
        )

    # ------------------------------------------------------------------ JobContext callbacks

    async def _check_cancelled(self, job_id: uuid.UUID) -> bool:
        async with self._storage.connection() as conn:
            return await self._storage.is_cancellation_requested(conn, job_id)

    async def _report_progress(
        self,
        *,
        job_id: uuid.UUID,
        progress: float,
        message: str | None,
        succeeded: int | None,
        failed: int | None,
        total: int | None,
    ) -> None:
        try:
            async with self._storage.connection() as conn:
                await self._storage.update_progress(
                    conn,
                    job_id=job_id,
                    progress=progress,
                    message=message,
                    succeeded=succeeded,
                    failed=failed,
                    total=total,
                )
                if self._scheduler_config.emit_events:
                    payload = self._events.job_progress(
                        job_id, progress, message, succeeded, failed, total
                    )
                    if payload is not None:
                        await self._storage.notify_event(conn, payload)
        except Exception:  # progress is observability — never fail a job over it
            log.debug("progress update failed for job %s", job_id, exc_info=True)

    def _on_cancel_signal(self, job_id: uuid.UUID) -> None:
        """A ``job_cancel`` notification arrived for a job that may be running here."""
        active = self._active.get(job_id)
        if active is None:
            return
        active.context._mark_cancelled()
        log.info("cancellation requested for running job %s", job_id)
        if active.force_cancel_handle is None:
            # Give the handler a chance to stop on its own; force it if it ignores the flag.
            loop = asyncio.get_running_loop()
            active.force_cancel_handle = loop.call_later(
                self._config.cancel_grace_seconds, self._force_cancel, job_id
            )

    def _force_cancel(self, job_id: uuid.UUID) -> None:
        active = self._active.get(job_id)
        if active is None or active.task.done():
            return
        log.warning(
            "job %s ignored the cancellation request for %.0fs — cancelling its task",
            job_id, self._config.cancel_grace_seconds,
        )
        active.task.cancel()

    # ------------------------------------------------------------------ background loops

    async def _heartbeat_loop(self) -> None:
        """Extend the lease on everything this node holds, in one statement per tick."""
        interval = self._config.heartbeat_interval_seconds
        while self._running:
            try:
                async with self._storage.connection() as conn:
                    extended = await self._storage.extend_locks(
                        conn, self._config.node_id, self._config.lock_duration_seconds
                    )
                if extended:
                    log.debug("extended %d lease(s)", extended)
            except asyncio.CancelledError:
                raise
            except Exception:
                log.warning("heartbeat tick failed — retrying next interval", exc_info=True)
            await asyncio.sleep(interval)

    async def _registry_loop(self) -> None:
        """Keep this node visible on the dashboard's Workers screen."""
        interval = self._config.heartbeat_interval_seconds
        while self._running:
            try:
                async with self._storage.connection() as conn:
                    await self._storage.upsert_worker_heartbeat(
                        conn,
                        node_id=self._config.node_id,
                        host=self._host,
                        tags=self._config.node_tags,
                        started_at=self._started_at,
                        in_flight_by_queue=self._in_flight.snapshot(),
                    )
            except asyncio.CancelledError:
                raise
            except Exception:
                log.warning("worker registry tick failed", exc_info=True)
            await asyncio.sleep(interval)

    # ------------------------------------------------------------------ helpers

    async def _emit_state(self, row: JobRow, from_state: JobState, to_state: JobState) -> None:
        if not self._scheduler_config.emit_events:
            return
        payload = self._events.job_state(row.id, from_state, to_state, row.queue)
        if payload is None:
            return
        try:
            async with self._storage.connection() as conn:
                await self._storage.notify_event(conn, payload)
        except Exception:
            log.debug("state event notify failed for job %s", row.id, exc_info=True)

    async def _announce(self, *, joined: bool) -> None:
        if not self._scheduler_config.emit_events:
            return
        payload = (
            self._events.worker_join(self._config.node_id, self._host)
            if joined
            else self._events.worker_leave(self._config.node_id)
        )
        if payload is None:
            return
        try:
            async with self._storage.connection() as conn:
                await self._storage.notify_event(conn, payload)
        except Exception:
            log.debug("worker presence notify failed", exc_info=True)

    @staticmethod
    def _resolve_host() -> str:
        try:
            return socket.gethostname()
        except OSError:
            return "unknown"
