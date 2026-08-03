"""RabbitMQ transport.

The wire format is deliberately tiny: **the message body is exactly the 16 raw bytes of the
job's UUID**, nothing else. Postgres holds the payload, so a delivery is only a hint that
some row is ready. That keeps messages cheap and makes a stale delivery harmless — the
worker's conditional pickup simply finds nothing to claim and acks.

Topology (declared identically by both language clients, so declaration is idempotent):

* ``jobs.dispatch`` — ``x-delayed-message`` exchange over ``direct``. One exchange serves
  immediate, scheduled and retried jobs; only the ``x-delay`` header differs.
* ``jobs.dlx`` -> ``q.dead-letter`` — where unparseable deliveries go for inspection.
* ``q.<name>`` — one queue per logical queue, ``x-max-priority=10``, dead-lettering to the
  DLX, bound to ``jobs.dispatch`` with the queue name as its routing key.

Redelivery is never left to the broker (``requeue=False`` everywhere): retries are owned by
the state machine in Postgres, which is the only place that knows the attempt budget.
"""

from __future__ import annotations

import contextlib
import logging
import uuid
from collections.abc import Awaitable, Callable
from typing import Any

import aio_pika
from aio_pika.abc import (
    AbstractChannel,
    AbstractIncomingMessage,
    AbstractRobustConnection,
)

from .config import RabbitConfig

log = logging.getLogger(__name__)

__all__ = ["RabbitTransport", "DISPATCH_EXCHANGE", "DLX_EXCHANGE", "DEAD_LETTER_QUEUE"]

DISPATCH_EXCHANGE = "jobs.dispatch"
DLX_EXCHANGE = "jobs.dlx"
DEAD_LETTER_QUEUE = "q.dead-letter"

MAX_PRIORITY = 10
_UUID_BYTES = 16

DeliveryHandler = Callable[[uuid.UUID], Awaitable[None]]


class RabbitTransport:
    """One connection, one channel per consumed queue."""

    def __init__(self, config: RabbitConfig) -> None:
        self._config = config
        self._connection: AbstractRobustConnection | None = None
        self._channels: list[AbstractChannel] = []

    async def start(self) -> None:
        if self._connection is not None:
            return
        self._connection = await aio_pika.connect_robust(
            self._config.url,
            client_properties={"connection_name": "taskscheduler-python"},
        )
        if self._config.declare_topology:
            channel = await self._connection.channel()
            try:
                await self._declare_topology(channel)
            finally:
                await channel.close()
        log.info("connected to RabbitMQ, queues=%s", self._config.queues)

    async def stop_consumers(self) -> None:
        """Close the consumer channels so the broker stops delivering.

        Separate from :meth:`close` so a graceful shutdown can stop *new* work while
        in-flight jobs keep running — their acks go through channels that are already gone,
        which is harmless: the lease and the state machine, not the ack, decide the outcome.
        """
        for channel in self._channels:
            if not channel.is_closed:
                with contextlib.suppress(Exception):
                    await channel.close()
        self._channels.clear()

    async def close(self) -> None:
        await self.stop_consumers()
        if self._connection is not None and not self._connection.is_closed:
            await self._connection.close()
        self._connection = None

    async def _declare_topology(self, channel: AbstractChannel) -> None:
        dispatch = await channel.declare_exchange(
            DISPATCH_EXCHANGE,
            "x-delayed-message",
            durable=True,
            arguments={"x-delayed-type": "direct"},
        )
        dlx = await channel.declare_exchange(
            DLX_EXCHANGE, aio_pika.ExchangeType.DIRECT, durable=True
        )
        dead_letter = await channel.declare_queue(DEAD_LETTER_QUEUE, durable=True)
        await dead_letter.bind(dlx, routing_key="")

        for name in self._config.queues:
            queue = await channel.declare_queue(
                f"q.{name}",
                durable=True,
                arguments={"x-max-priority": MAX_PRIORITY, "x-dead-letter-exchange": DLX_EXCHANGE},
            )
            await queue.bind(dispatch, routing_key=name)

    async def consume(
        self, queue_name: str, prefetch: int, handler: DeliveryHandler
    ) -> AbstractChannel:
        """Subscribe to ``q.<queue_name>``.

        The handler owns the outcome: returning normally acks, raising nacks to the DLX. It
        is expected to swallow business failures itself (they belong in the job's state
        machine), so a nack here really does mean "this delivery is unusable".
        """
        if self._connection is None:
            raise RuntimeError("RabbitTransport.start() has not been awaited")
        channel = await self._connection.channel()
        await channel.set_qos(prefetch_count=prefetch)
        queue = await channel.get_queue(f"q.{queue_name}", ensure=False)

        async def on_message(message: AbstractIncomingMessage) -> None:
            body = message.body
            if body is None or len(body) != _UUID_BYTES:
                log.warning(
                    "unexpected body size %s on q.%s — routing to the DLX",
                    0 if body is None else len(body),
                    queue_name,
                )
                await message.nack(requeue=False)
                return
            job_id = uuid.UUID(bytes=body)
            try:
                await handler(job_id)
            except Exception:
                log.exception("delivery handler failed for job %s on q.%s", job_id, queue_name)
                await message.nack(requeue=False)
            else:
                await message.ack()

        await queue.consume(on_message, no_ack=False)
        self._channels.append(channel)
        log.info("consuming q.%s with prefetch=%s", queue_name, prefetch)
        return channel

    async def publish(
        self, job_id: uuid.UUID, routing_key: str, priority: int = 0, delay_ms: int = 0
    ) -> None:
        """Publish a dispatch directly.

        Normally unnecessary: enqueue writes an outbox row and the infra leader publishes it,
        which is what keeps the broker and the database from diverging. This exists for tests
        and for redelivering a message by hand.
        """
        if self._connection is None:
            raise RuntimeError("RabbitTransport.start() has not been awaited")
        channel = await self._connection.channel()
        try:
            exchange = await channel.get_exchange(DISPATCH_EXCHANGE, ensure=False)
            headers: dict[str, Any] = {"x-delay": int(delay_ms)} if delay_ms > 0 else {}
            await exchange.publish(
                aio_pika.Message(
                    body=job_id.bytes,
                    delivery_mode=aio_pika.DeliveryMode.PERSISTENT,
                    priority=max(0, min(MAX_PRIORITY, priority)),
                    headers=headers,
                ),
                routing_key=routing_key,
            )
        finally:
            await channel.close()
