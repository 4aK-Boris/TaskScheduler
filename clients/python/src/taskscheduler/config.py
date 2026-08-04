"""Configuration objects.

Three groups, matching the Kotlin Koin modules: core scheduler defaults
(``schedulerCoreModule``), the RabbitMQ connection (``schedulerRabbitModule``), and the
worker pool (``schedulerWorkerModule``).

This client never runs migrations. The Kotlin ``scheduler-infra`` process owns the schema
and the background loops (outbox publisher, recurring cron, retention, orphan recovery);
the Python service is a peer of a user-app worker, not a replacement for infra.
"""

from __future__ import annotations

import os
import socket
from dataclasses import dataclass, field

from .errors import ConfigurationError
from .retry import ExponentialBackoff, RetryPolicy

__all__ = [
    "SchedulerConfig",
    "RabbitConfig",
    "WorkerConfig",
    "QueueConfig",
    "dsn_from_jdbc",
    "default_node_id",
]

#: The Flyway version this client's SQL was written against. Startup fails if the database
#: is older, because the missing columns would surface as confusing runtime errors instead.
REQUIRED_SCHEMA_VERSION = 8


def default_node_id() -> str:
    """``HOSTNAME`` / ``COMPUTERNAME`` / the host name, matching Kotlin's ``defaultNodeId``."""
    for var in ("HOSTNAME", "COMPUTERNAME"):
        value = os.environ.get(var)
        if value:
            return value
    try:
        return socket.gethostname()
    except OSError:
        return "worker"


def dsn_from_jdbc(jdbc_url: str, user: str, password: str) -> str:
    """Turn the ``jdbc:postgresql://host:port/db`` URL used by the Kotlin side into a libpq DSN.

    Handy when both services read the same ``POSTGRES_URL`` environment variable.
    """
    prefix = "jdbc:postgresql://"
    if not jdbc_url.startswith(prefix):
        raise ConfigurationError(f"expected a URL starting with {prefix!r}, got {jdbc_url!r}")
    return f"postgresql://{user}:{password}@{jdbc_url[len(prefix):]}"


@dataclass(slots=True)
class SchedulerConfig:
    """Connection to Postgres plus the defaults applied to every enqueue."""

    dsn: str
    node_id: str = field(default_factory=default_node_id)
    default_queue: str = "default"
    default_max_attempts: int = 3
    default_timeout_seconds: int = 300
    default_retry_policy: RetryPolicy | None = None
    #: Jobs further out than this are stored as SCHEDULED without an outbox row; the infra
    #: fast-forward loop promotes them when they come within the window. Must match the
    #: Kotlin ``fastForwardWindow`` (24h) or far-future jobs fire at the wrong time.
    fast_forward_window_seconds: int = 24 * 60 * 60
    min_pool_size: int = 1
    max_pool_size: int = 10
    #: Emit ``scheduler_events`` NOTIFY payloads so the dashboard updates live.
    emit_events: bool = True
    #: Verify the Flyway schema version at startup.
    check_schema: bool = True

    def __post_init__(self) -> None:
        if not self.dsn:
            raise ConfigurationError("SchedulerConfig.dsn is required")
        if self.default_max_attempts < 1:
            raise ConfigurationError("default_max_attempts must be >= 1")
        if self.default_timeout_seconds < 1:
            raise ConfigurationError("default_timeout_seconds must be >= 1")
        if self.max_pool_size < self.min_pool_size:
            raise ConfigurationError("max_pool_size must be >= min_pool_size")
        if self.default_retry_policy is None:
            self.default_retry_policy = ExponentialBackoff(max_attempts=self.default_max_attempts)


@dataclass(slots=True)
class RabbitConfig:
    """AMQP connection settings. Only the worker needs these — producers write to Postgres."""

    url: str = "amqp://guest:guest@localhost:5672/"
    #: Logical queue names to declare. Must be a superset of the names the worker consumes,
    #: and must match what the Kotlin side declares, or the bindings will not line up.
    queues: list[str] = field(default_factory=lambda: ["default"])
    prefetch: int = 10
    reconnect_delay_seconds: float = 5.0
    #: Declare the exchanges and queues on connect. Leave on unless a stricter broker policy
    #: forbids clients from declaring topology.
    declare_topology: bool = True

    def __post_init__(self) -> None:
        if not self.url:
            raise ConfigurationError("RabbitConfig.url is required")
        if self.prefetch < 1:
            raise ConfigurationError("prefetch must be >= 1")


@dataclass(slots=True)
class QueueConfig:
    """One consumed queue and how much of it runs at once."""

    name: str
    concurrency: int = 10
    prefetch: int | None = None
    default_priority: int = 0

    def __post_init__(self) -> None:
        if self.concurrency < 1:
            raise ConfigurationError(f"queue {self.name!r}: concurrency must be >= 1")
        if self.prefetch is None:
            self.prefetch = self.concurrency
        if self.prefetch < 1:
            raise ConfigurationError(f"queue {self.name!r}: prefetch must be >= 1")
        if not 0 <= self.default_priority <= 10:
            raise ConfigurationError(f"queue {self.name!r}: default_priority must be within 0..10")


@dataclass(slots=True)
class WorkerConfig:
    """Worker identity, lease timings and the queues it serves."""

    node_id: str = field(default_factory=default_node_id)
    node_tags: list[str] = field(default_factory=list)
    queues: list[QueueConfig] = field(default_factory=list)
    #: How often the lease on in-flight jobs is extended, and how long each extension lasts.
    #: Keep ``heartbeat <= lock_duration / 3`` so two missed ticks still leave the lock valid
    #: (DESIGN.md 13.4) — otherwise infra's orphan recovery re-runs a job that is still going.
    heartbeat_interval_seconds: float = 30.0
    lock_duration_seconds: float = 90.0
    #: Grace period for in-flight jobs to finish after ``stop()`` is called.
    shutdown_timeout_seconds: float = 30.0
    #: How long a cancelled job is given to stop cooperatively before its task is cancelled
    #: outright. Handlers that never check ``ctx.is_cancellation_requested()`` need this;
    #: ones that do stop sooner.
    cancel_grace_seconds: float = 30.0
    #: How long a job whose type is paused waits before redelivery. Matches Kotlin's
    #: ``PAUSE_REDELIVER_DELAY``.
    pause_redeliver_delay_seconds: float = 60.0

    def queue(
        self,
        name: str,
        *,
        concurrency: int = 10,
        prefetch: int | None = None,
        default_priority: int = 0,
    ) -> WorkerConfig:
        """Add a consumed queue. Chainable."""
        self.queues.append(
            QueueConfig(
                name=name,
                concurrency=concurrency,
                prefetch=prefetch,
                default_priority=default_priority,
            )
        )
        return self

    def validate(self) -> None:
        if not self.queues:
            raise ConfigurationError(
                "WorkerConfig has no queues — call worker_config.queue('default') at least once"
            )
        names = [q.name for q in self.queues]
        duplicates = {n for n in names if names.count(n) > 1}
        if duplicates:
            raise ConfigurationError(f"duplicate queue names: {', '.join(sorted(duplicates))}")
        if self.heartbeat_interval_seconds <= 0 or self.lock_duration_seconds <= 0:
            raise ConfigurationError("heartbeat_interval and lock_duration must be positive")
        if self.heartbeat_interval_seconds > self.lock_duration_seconds / 3:
            raise ConfigurationError(
                f"heartbeat_interval ({self.heartbeat_interval_seconds}s) must be at most a third "
                f"of lock_duration ({self.lock_duration_seconds}s) — otherwise a single missed "
                f"tick lets another node steal a job that is still running"
            )
