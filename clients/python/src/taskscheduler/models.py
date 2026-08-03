"""Wire-level enums and row models.

Every string value here is part of the cross-language contract: it is written to (or read
from) the shared Postgres schema that the Kotlin ``scheduler-infra`` process owns. Keep the
spellings identical to ``core/shared/.../JobState.kt`` and friends.
"""

from __future__ import annotations

import uuid
from dataclasses import dataclass
from datetime import datetime
from enum import Enum

__all__ = [
    "JobState",
    "OnFailure",
    "ConcurrencyPolicy",
    "MisfirePolicy",
    "RecurringOverlap",
    "JobRow",
    "MAX_PRIORITY",
    "MIN_PRIORITY",
]

MIN_PRIORITY = 0
MAX_PRIORITY = 10


class JobState(str, Enum):
    """Values of ``job.state``. Mirrors ``cs.trade.scheduler.shared.JobState``."""

    AWAITING_DEPS = "AWAITING_DEPS"
    SCHEDULED = "SCHEDULED"
    ENQUEUED = "ENQUEUED"
    PROCESSING = "PROCESSING"
    AWAITING_RETRY = "AWAITING_RETRY"
    SUCCEEDED = "SUCCEEDED"
    FAILED = "FAILED"
    CANCELLED = "CANCELLED"

    @property
    def is_terminal(self) -> bool:
        return self in (JobState.SUCCEEDED, JobState.FAILED, JobState.CANCELLED)


class OnFailure(str, Enum):
    """What happens to a DAG child when its parent fails."""

    PROPAGATE_FAILURE = "PROPAGATE_FAILURE"
    CANCEL_CHILD = "CANCEL_CHILD"
    IGNORE = "IGNORE"


class ConcurrencyPolicy(str, Enum):
    """Collision handling for :meth:`Scheduler.enqueue_once`."""

    SKIP = "SKIP"
    REPLACE = "REPLACE"
    ENQUEUE_AFTER = "ENQUEUE_AFTER"


class MisfirePolicy(str, Enum):
    """What a recurring definition does about cron slots missed during downtime."""

    SKIP = "SKIP"
    CATCH_UP_ONE = "CATCH_UP_ONE"
    CATCH_UP_ALL = "CATCH_UP_ALL"


class RecurringOverlap(str, Enum):
    """What happens when a recurring job fires while its previous run is still active."""

    ALLOW = "ALLOW"
    SKIP = "SKIP"
    REPLACE = "REPLACE"


@dataclass(slots=True)
class JobRow:
    """A row of the ``job`` table, as read back at pickup time.

    Only the columns the worker actually needs are mapped — the dashboard reads the rest
    directly. ``version`` is the optimistic-locking token: every state transition this
    client performs is a CAS on it.
    """

    id: uuid.UUID
    state: JobState
    queue: str
    priority: int
    payload_type: str
    payload_json: str
    attempts: int
    max_attempts: int
    version: int
    pending_deps: int
    scheduled_at: datetime | None = None
    timeout_seconds: int | None = None
    locked_by: str | None = None
    locked_until: datetime | None = None
    target_node: str | None = None
    target_tag: str | None = None
    started_at: datetime | None = None
    cancel_requested_at: datetime | None = None
    created_at: datetime | None = None

    @property
    def routing_key(self) -> str:
        """Rabbit routing key for this row — ``node.* > tag.* > queue``.

        Same precedence as ``DefaultScheduler.routingKeyForJob``; a mismatch here would
        strand retried jobs on a queue nobody consumes.
        """
        if self.target_node is not None:
            return f"node.{self.target_node}"
        if self.target_tag is not None:
            return f"tag.{self.target_tag}"
        return self.queue
