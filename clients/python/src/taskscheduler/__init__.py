"""TaskScheduler — async Python SDK for durable, distributed background jobs.

A Python service talks to the same PostgreSQL database and RabbitMQ broker as the Kotlin
side, so its jobs show up on the same dashboard, obey the same retry and cancellation
semantics, and survive restarts the same way. Postgres holds the state; RabbitMQ only
delivers 16-byte job ids.

Producing work::

    from dataclasses import dataclass
    from taskscheduler import Scheduler, SchedulerConfig, job_type

    @job_type
    @dataclass
    class SendInvoice:
        order_id: int

    async with Scheduler(SchedulerConfig(dsn="postgresql://...")) as scheduler:
        await scheduler.enqueue(SendInvoice(order_id=42))

Consuming it::

    from taskscheduler import HandlerRegistry, JobContext, WorkerPool, WorkerConfig

    registry = HandlerRegistry()

    @registry.handler(SendInvoice)
    async def send_invoice(ctx: JobContext, job: SendInvoice) -> None:
        await billing.send(job.order_id)

    worker = WorkerPool(
        scheduler_config=SchedulerConfig(dsn="postgresql://..."),
        worker_config=WorkerConfig(node_id="billing-1").queue("billing", concurrency=8),
        rabbit_config=RabbitConfig(url="amqp://...", queues=["billing"]),
        registry=registry,
    )
    await worker.start()

The delivery guarantee is at-least-once: a job can run twice after a lease expiry or a
broker redelivery. ``ctx.job_id`` is stable across every attempt — use it as the
idempotency key for anything with side effects.

Requires a running Kotlin ``scheduler-infra`` process: it owns the schema migrations and
the background loops (outbox publisher, recurring cron, orphan recovery, retention).
"""

from __future__ import annotations

from .config import (
    QueueConfig,
    RabbitConfig,
    SchedulerConfig,
    WorkerConfig,
    default_node_id,
    dsn_from_jdbc,
)
from .context import JobContext, ProgressBar
from .cron import next_trigger_at, validate_cron
from .errors import (
    ConfigurationError,
    HandlerNotRegisteredError,
    JobCancellationError,
    NonRetriableError,
    PayloadDecodeError,
    SchedulerError,
    SchemaMismatchError,
)
from .job import HandlerRegistry, JobHandler, job_type
from .models import (
    ConcurrencyPolicy,
    JobRow,
    JobState,
    MisfirePolicy,
    OnFailure,
    RecurringOverlap,
)
from .retry import (
    FULL_JITTER,
    NO_JITTER,
    ExponentialBackoff,
    FixedDelay,
    Jitter,
    NoRetry,
    RetryPolicy,
    equal_jitter,
)
from .scheduler import Scheduler
from .storage import Storage
from .transport import RabbitTransport
from .worker import WorkerPool

__version__ = "0.7.0"

__all__ = [
    "__version__",
    # entry points
    "Scheduler",
    "WorkerPool",
    "HandlerRegistry",
    "JobHandler",
    "JobContext",
    "ProgressBar",
    "job_type",
    # configuration
    "SchedulerConfig",
    "WorkerConfig",
    "QueueConfig",
    "RabbitConfig",
    "default_node_id",
    "dsn_from_jdbc",
    # retry
    "RetryPolicy",
    "NoRetry",
    "FixedDelay",
    "ExponentialBackoff",
    "Jitter",
    "NO_JITTER",
    "FULL_JITTER",
    "equal_jitter",
    # enums and rows
    "JobState",
    "JobRow",
    "OnFailure",
    "ConcurrencyPolicy",
    "MisfirePolicy",
    "RecurringOverlap",
    # cron
    "next_trigger_at",
    "validate_cron",
    # errors
    "SchedulerError",
    "ConfigurationError",
    "SchemaMismatchError",
    "PayloadDecodeError",
    "HandlerNotRegisteredError",
    "JobCancellationError",
    "NonRetriableError",
    # lower level
    "Storage",
    "RabbitTransport",
]
