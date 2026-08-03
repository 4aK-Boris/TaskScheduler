"""Dashboard events over Postgres LISTEN/NOTIFY.

Two channels are shared with the Kotlin side:

* ``scheduler_events`` — an envelope ``{"origin": nodeId, "event": {...}}`` that every
  replica broadcasts and the dashboard forwards to browsers over WebSocket. The event
  objects use ``_type`` as their discriminator, matching ``WebSocketEvent`` in
  ``core/shared``. Purely observational: a dropped notify costs a live update, nothing more.
* ``job_cancel`` — a bare job-id string telling whichever node is running that job to stop.
  This is the push half of cancellation; :meth:`JobContext.is_cancellation_requested` is the
  poll half, and a handler is free to use either.
"""

from __future__ import annotations

import asyncio
import contextlib
import json
import logging
import uuid
from collections.abc import Callable
from datetime import datetime, timezone
from typing import Any

import psycopg

from .models import JobState

log = logging.getLogger(__name__)

__all__ = ["EventEncoder", "JobCancelListener"]


def _now_iso() -> str:
    return datetime.now(timezone.utc).isoformat().replace("+00:00", "Z")


class EventEncoder:
    """Builds ``scheduler_events`` envelopes for this node."""

    #: Postgres caps a NOTIFY payload at 8000 bytes; leave headroom like the Kotlin bus does.
    PAYLOAD_LIMIT = 7_500

    def __init__(self, node_id: str) -> None:
        self._node_id = node_id

    def _envelope(self, event: dict[str, Any]) -> str | None:
        event.setdefault("at", _now_iso())
        payload = json.dumps(
            {"origin": self._node_id, "event": event}, ensure_ascii=False, separators=(",", ":")
        )
        if len(payload.encode("utf-8")) > self.PAYLOAD_LIMIT:
            log.warning("dropping oversized %s event for the dashboard", event.get("_type"))
            return None
        return payload

    def job_created(self, job_id: uuid.UUID, queue: str, payload_type: str) -> str | None:
        return self._envelope(
            {"_type": "job_created", "id": str(job_id), "queue": queue, "type": payload_type}
        )

    def job_state(
        self, job_id: uuid.UUID, from_state: JobState, to_state: JobState, queue: str
    ) -> str | None:
        return self._envelope(
            {
                "_type": "job_state",
                "id": str(job_id),
                "from": from_state.value,
                "to": to_state.value,
                "queue": queue,
            }
        )

    def job_progress(
        self,
        job_id: uuid.UUID,
        progress: float,
        message: str | None = None,
        succeeded: int | None = None,
        failed: int | None = None,
        total: int | None = None,
    ) -> str | None:
        event: dict[str, Any] = {"_type": "job_progress", "id": str(job_id), "progress": progress}
        if message is not None:
            event["msg"] = message
        if succeeded is not None:
            event["succeeded"] = succeeded
        if failed is not None:
            event["failed"] = failed
        if total is not None:
            event["total"] = total
        return self._envelope(event)

    def worker_join(self, node_id: str, host: str) -> str | None:
        return self._envelope({"_type": "worker_join", "nodeId": node_id, "host": host})

    def worker_leave(self, node_id: str) -> str | None:
        return self._envelope({"_type": "worker_leave", "nodeId": node_id})


class JobCancelListener:
    """Holds one dedicated connection in LISTEN mode on ``job_cancel``.

    A dedicated connection, not a pooled one: LISTEN occupies a session for as long as it is
    active, and tying up a pool slot would starve real work. Delivery is best-effort — a
    notification that arrives mid-reconnect is lost, which is why cancellation is also
    pollable from the job context.
    """

    CHANNEL = "job_cancel"

    def __init__(self, dsn: str, on_cancel: Callable[[uuid.UUID], None]) -> None:
        self._dsn = dsn
        self._on_cancel = on_cancel
        self._task: asyncio.Task[None] | None = None
        self._reconnect_delay = 5.0

    def start(self) -> None:
        if self._task is None or self._task.done():
            self._task = asyncio.create_task(self._run(), name="taskscheduler-cancel-listener")

    async def stop(self) -> None:
        if self._task is None:
            return
        self._task.cancel()
        with contextlib.suppress(asyncio.CancelledError):
            await self._task
        self._task = None

    async def _run(self) -> None:
        while True:
            try:
                await self._listen_loop()
            except asyncio.CancelledError:
                raise
            except Exception:
                log.warning(
                    "job_cancel listener died — reconnecting in %.0fs", self._reconnect_delay,
                    exc_info=True,
                )
                await asyncio.sleep(self._reconnect_delay)

    async def _listen_loop(self) -> None:
        conn = await psycopg.AsyncConnection.connect(self._dsn, autocommit=True)
        try:
            await conn.execute(f'LISTEN "{self.CHANNEL}"')
            log.info("listening for cancellations on %s", self.CHANNEL)
            async for notify in conn.notifies():
                try:
                    job_id = uuid.UUID(notify.payload)
                except ValueError:
                    log.warning(
                        "job_cancel carried a non-UUID payload %r — ignoring", notify.payload
                    )
                    continue
                self._on_cancel(job_id)
        finally:
            await conn.close()
