"""Per-execution context handed to a handler.

``job_id`` is stable across every attempt of a job — retries, orphan recovery and broker
redelivery all reuse it. That makes it the natural idempotency key for external calls
(``Idempotency-Key: <job_id>`` on an HTTP request, a unique column in your own tables),
which matters because the delivery guarantee is at-least-once: a job can run twice, and
only the handler can make that harmless.
"""

from __future__ import annotations

import time
import uuid
from datetime import datetime
from typing import TYPE_CHECKING

from .models import JobRow

if TYPE_CHECKING:
    from .worker import WorkerPool

__all__ = ["JobContext", "ProgressBar"]

#: Progress writes are collapsed to at most one per second per job. Calling more often is
#: safe — only the latest value in each window reaches the database.
_PROGRESS_THROTTLE_SECONDS = 1.0


class ProgressBar:
    """Counting progress over a known number of work items.

        bar = ctx.progress_bar(len(orders))
        for order in orders:
            try:
                await charge(order)
                await bar.succeeded()
            except PaymentDeclined:
                await bar.failed()

    The fraction is derived from the counters and persisted under the same throttle as
    :meth:`JobContext.update_progress`. Creating a bar costs nothing — the first write
    happens on the first increment.
    """

    __slots__ = ("_ctx", "_total", "_succeeded", "_failed")

    def __init__(self, ctx: JobContext, total: int) -> None:
        self._ctx = ctx
        self._total = max(0, total)
        self._succeeded = 0
        self._failed = 0

    @property
    def total(self) -> int:
        return self._total

    @property
    def succeeded_count(self) -> int:
        return self._succeeded

    @property
    def failed_count(self) -> int:
        return self._failed

    @property
    def processed(self) -> int:
        return self._succeeded + self._failed

    @property
    def fraction(self) -> float:
        if self._total <= 0:
            return 0.0
        return min(1.0, self.processed / self._total)

    async def succeeded(self, count: int = 1, message: str | None = None) -> None:
        self._succeeded += count
        await self._flush(message)

    async def failed(self, count: int = 1, message: str | None = None) -> None:
        self._failed += count
        await self._flush(message)

    async def _flush(self, message: str | None) -> None:
        await self._ctx._write_progress(
            progress=self.fraction,
            message=message,
            succeeded=self._succeeded,
            failed=self._failed,
            total=self._total,
        )


class JobContext:
    """What a handler knows about the execution it is in."""

    __slots__ = ("_row", "_pool", "_last_progress_at", "_cancel_flag")

    def __init__(self, row: JobRow, pool: WorkerPool) -> None:
        self._row = row
        self._pool = pool
        self._last_progress_at = 0.0
        self._cancel_flag = False

    @property
    def job_id(self) -> uuid.UUID:
        return self._row.id

    @property
    def attempt(self) -> int:
        """1-based: the first execution is attempt 1."""
        return self._row.attempts

    @property
    def max_attempts(self) -> int:
        return self._row.max_attempts

    @property
    def queue(self) -> str:
        return self._row.queue

    @property
    def payload_type(self) -> str:
        return self._row.payload_type

    @property
    def enqueued_at(self) -> datetime | None:
        return self._row.created_at

    @property
    def is_last_attempt(self) -> bool:
        return self._row.attempts >= self._row.max_attempts

    def progress_bar(self, total: int) -> ProgressBar:
        return ProgressBar(self, total)

    async def update_progress(self, progress: float, message: str | None = None) -> None:
        """Report a 0.0..1.0 fraction. Throttled to one write per second."""
        await self._write_progress(progress=max(0.0, min(1.0, progress)), message=message)

    async def is_cancellation_requested(self) -> bool:
        """Whether someone asked this job to stop.

        Poll it inside long loops and raise :class:`JobCancellationError` to end as CANCELLED::

            if await ctx.is_cancellation_requested():
                raise JobCancellationError()

        Returning normally after a cancel request is also fine — the job lands in SUCCEEDED
        because the work actually finished.
        """
        if self._cancel_flag:
            return True
        self._cancel_flag = await self._pool._check_cancelled(self._row.id)
        return self._cancel_flag

    def _mark_cancelled(self) -> None:
        """Set by the ``job_cancel`` listener so the next poll answers without a query."""
        self._cancel_flag = True

    async def _write_progress(
        self,
        *,
        progress: float,
        message: str | None,
        succeeded: int | None = None,
        failed: int | None = None,
        total: int | None = None,
    ) -> None:
        now = time.monotonic()
        complete = total is not None and succeeded is not None and failed is not None and (
            succeeded + failed >= total
        )
        # Always let the final tick through, so a bar that finishes inside the throttle
        # window doesn't leave the dashboard stuck at 90%.
        if not complete and now - self._last_progress_at < _PROGRESS_THROTTLE_SECONDS:
            return
        self._last_progress_at = now
        await self._pool._report_progress(
            job_id=self._row.id,
            progress=progress,
            message=message,
            succeeded=succeeded,
            failed=failed,
            total=total,
        )
