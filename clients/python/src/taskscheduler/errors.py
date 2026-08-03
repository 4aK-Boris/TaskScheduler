"""Exceptions raised by the TaskScheduler Python SDK."""

from __future__ import annotations

__all__ = [
    "SchedulerError",
    "ConfigurationError",
    "SchemaMismatchError",
    "PayloadDecodeError",
    "HandlerNotRegisteredError",
    "JobCancellationError",
    "NonRetriableError",
]


class SchedulerError(Exception):
    """Base class for every error this SDK raises."""


class ConfigurationError(SchedulerError):
    """Invalid or incomplete configuration — raised eagerly at construction time."""


class SchemaMismatchError(SchedulerError):
    """The database schema is older than this client expects.

    The Kotlin ``scheduler-infra`` process owns the Flyway schema; this client only
    verifies it. Deploy a matching infra version before starting the Python service.
    """


class PayloadDecodeError(SchedulerError):
    """``payload_json`` could not be turned back into a payload object.

    Treated as terminal: the bytes will not change, so retrying is pointless. Mirrors the
    Kotlin worker's ``SerializationException`` handling (DESIGN.md 22.9).
    """


class HandlerNotRegisteredError(SchedulerError):
    """No handler is registered for a job's ``payload_type`` on this node.

    Also terminal — the job is marked FAILED so the dashboard surfaces the
    misconfiguration instead of the message bouncing between nodes.
    """


class JobCancellationError(SchedulerError):
    """Raise from a handler to end the job as CANCELLED rather than FAILED.

    No retry is scheduled and ``on_final_failure`` is not invoked.
    """


class NonRetriableError(SchedulerError):
    """Raise from a handler to fail terminally, skipping the remaining attempt budget."""
