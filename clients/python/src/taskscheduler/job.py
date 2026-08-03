"""Payload declaration and handler registration.

A *payload* is a plain dataclass (or pydantic model) describing the arguments of one unit
of work. A *handler* is the code that runs it. The two are bound by ``payload_type``, the
same string the Kotlin side keeps in ``job.payload_type``.

    @job_type
    @dataclass
    class SendEmail:
        user_id: int
        template: str

    registry = HandlerRegistry()

    @registry.handler(SendEmail)
    async def send_email(ctx: JobContext, job: SendEmail) -> None:
        await mailer.send(job.user_id, job.template)

The ``(ctx, job)`` argument order matches ``JobHandler.execute(ctx, job)`` on the Kotlin
side, so the two implementations read the same way.
"""

from __future__ import annotations

import inspect
from collections.abc import Awaitable, Callable
from dataclasses import dataclass
from typing import TYPE_CHECKING, Any, Generic, TypeVar, overload

from .errors import ConfigurationError
from .retry import RetryPolicy
from .serde import payload_type_of

if TYPE_CHECKING:
    from .context import JobContext

__all__ = ["job_type", "JobHandler", "HandlerRegistry", "HandlerEntry"]

P = TypeVar("P")

HandlerFn = Callable[["JobContext", Any], Awaitable[None]]
FailureFn = Callable[["JobContext", Any, BaseException], Awaitable[None]]


@overload
def job_type(arg: type[P], /) -> type[P]: ...


@overload
def job_type(arg: str, /) -> Callable[[type[P]], type[P]]: ...


def job_type(arg: Any, /) -> Any:
    """Mark a class as a job payload, optionally pinning its ``payload_type``.

    Bare ``@job_type`` derives the type name from the module and class name
    (``billing.jobs.SendInvoice``). Passing a string pins it explicitly, which is what you
    want before renaming or moving the class — the stored name must keep matching the jobs
    already sitting in the queue.
    """
    if isinstance(arg, str):
        name = arg

        def decorate(cls: type[P]) -> type[P]:
            cls.__taskscheduler_type__ = name  # type: ignore[attr-defined]
            return cls

        return decorate

    arg.__taskscheduler_type__ = payload_type_of(arg)
    return arg


class JobHandler(Generic[P]):
    """Class-based handler. Set :attr:`payload` to the payload type it accepts.

        class SendEmailHandler(JobHandler[SendEmail]):
            payload = SendEmail

            async def execute(self, ctx: JobContext, job: SendEmail) -> None:
                ...
    """

    payload: type[P]
    retry_policy: RetryPolicy | None = None
    default_priority: int = 0

    async def execute(self, ctx: JobContext, job: P) -> None:
        raise NotImplementedError

    async def on_final_failure(self, ctx: JobContext, job: P, error: BaseException) -> None:
        """Called once after the job is finally FAILED (budget exhausted or non-retriable).

        Exceptions raised here are logged and swallowed — a cleanup hook must never take
        the consumer down.
        """
        return None


@dataclass(slots=True)
class HandlerEntry:
    """One registered handler, resolved by ``payload_type`` at pickup time."""

    payload_type: str
    payload_cls: type
    execute: HandlerFn
    on_final_failure: FailureFn | None = None
    retry_policy: RetryPolicy | None = None
    default_priority: int = 0
    max_attempts: int | None = None
    timeout_seconds: int | None = None


class HandlerRegistry:
    """In-process map of ``payload_type -> handler``.

    Nothing about this registry is published to Postgres or RabbitMQ — like the Kotlin
    ``HandlerRegistry``, it is purely local. A job whose type is not registered on the node
    that picks it up is marked FAILED rather than passed along, so keep each queue served
    by nodes that agree on its types.
    """

    def __init__(self) -> None:
        self._entries: dict[str, HandlerEntry] = {}

    def handler(
        self,
        payload_cls: type[P],
        *,
        retry_policy: RetryPolicy | None = None,
        default_priority: int = 0,
        max_attempts: int | None = None,
        timeout_seconds: int | None = None,
        on_final_failure: FailureFn | None = None,
    ) -> Callable[[HandlerFn], HandlerFn]:
        """Decorator registering an ``async def fn(ctx, job)`` for ``payload_cls``."""

        def decorate(fn: HandlerFn) -> HandlerFn:
            if not inspect.iscoroutinefunction(fn):
                raise ConfigurationError(
                    f"handler {fn.__qualname__} for {payload_cls.__name__} must be "
                    f"`async def` — this SDK runs handlers on the event loop"
                )
            self._add(
                HandlerEntry(
                    payload_type=payload_type_of(payload_cls),
                    payload_cls=payload_cls,
                    execute=fn,
                    on_final_failure=on_final_failure,
                    retry_policy=retry_policy,
                    default_priority=default_priority,
                    max_attempts=max_attempts,
                    timeout_seconds=timeout_seconds,
                )
            )
            return fn

        return decorate

    def register(
        self,
        handler: JobHandler[Any],
        *,
        max_attempts: int | None = None,
        timeout_seconds: int | None = None,
    ) -> None:
        """Register a class-based :class:`JobHandler` instance."""
        payload_cls = getattr(handler, "payload", None)
        if payload_cls is None:
            raise ConfigurationError(
                f"{type(handler).__name__} must set a `payload` class attribute naming the "
                f"payload type it handles"
            )
        self._add(
            HandlerEntry(
                payload_type=payload_type_of(payload_cls),
                payload_cls=payload_cls,
                execute=handler.execute,
                on_final_failure=handler.on_final_failure,
                retry_policy=handler.retry_policy,
                default_priority=handler.default_priority,
                max_attempts=max_attempts,
                timeout_seconds=timeout_seconds,
            )
        )

    def _add(self, entry: HandlerEntry) -> None:
        existing = self._entries.get(entry.payload_type)
        if existing is not None:
            raise ConfigurationError(
                f"two handlers registered for payload_type {entry.payload_type}: "
                f"{existing.execute.__qualname__} and {entry.execute.__qualname__}"
            )
        self._entries[entry.payload_type] = entry

    def find(self, payload_type: str) -> HandlerEntry | None:
        return self._entries.get(payload_type)

    def for_payload(self, payload: Any) -> HandlerEntry | None:
        return self._entries.get(payload_type_of(type(payload)))

    @property
    def known_types(self) -> list[str]:
        return sorted(self._entries)

    def __len__(self) -> int:
        return len(self._entries)
