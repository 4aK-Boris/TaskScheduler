"""Fixtures for tests that need a real Postgres and RabbitMQ.

Skipped unless both endpoints are configured:

    export TASKSCHEDULER_TEST_DSN="postgresql://scheduler:scheduler@localhost:5432/scheduler"
    export TASKSCHEDULER_TEST_AMQP="amqp://scheduler:scheduler@localhost:5672/"

The database must already have the Flyway migrations applied — the Kotlin scheduler-infra
process owns them, so bring it up first (``docker compose up -d`` at the repo root).

Each test gets its own queue name so runs can overlap without stealing each other's jobs,
and every job created is deleted afterwards.
"""

from __future__ import annotations

import asyncio
import contextlib
import os
import sys
import uuid
from collections.abc import AsyncIterator

import pytest
import pytest_asyncio

from taskscheduler import (
    RabbitConfig,
    RabbitTransport,
    Scheduler,
    SchedulerConfig,
    Storage,
    WorkerConfig,
)

DSN = os.environ.get("TASKSCHEDULER_TEST_DSN")
AMQP = os.environ.get("TASKSCHEDULER_TEST_AMQP")

pytestmark = pytest.mark.skipif(
    not DSN or not AMQP,
    reason="set TASKSCHEDULER_TEST_DSN and TASKSCHEDULER_TEST_AMQP to run integration tests",
)


def pytest_asyncio_loop_factories(config: object, item: object) -> dict[str, object]:
    """psycopg cannot drive Windows' default ProactorEventLoop — use the selector one."""
    if sys.platform == "win32":
        return {"selector": asyncio.SelectorEventLoop}
    return {"default": asyncio.new_event_loop}


@pytest.fixture(scope="session")
def dsn() -> str:
    if not DSN:
        pytest.skip("TASKSCHEDULER_TEST_DSN is not set")
    return DSN


@pytest.fixture(scope="session")
def amqp_url() -> str:
    if not AMQP:
        pytest.skip("TASKSCHEDULER_TEST_AMQP is not set")
    return AMQP


@pytest.fixture
def queue_name() -> str:
    """A queue unique to this test, so parallel runs cannot cross-consume."""
    return f"pytest-{uuid.uuid4().hex[:8]}"


@pytest.fixture
def node_id() -> str:
    return f"pytest-node-{uuid.uuid4().hex[:8]}"


@pytest.fixture
def scheduler_config(dsn: str, node_id: str) -> SchedulerConfig:
    return SchedulerConfig(
        dsn=dsn,
        node_id=node_id,
        default_max_attempts=3,
        default_timeout_seconds=30,
        max_pool_size=4,
    )


@pytest.fixture
def rabbit_config(amqp_url: str, queue_name: str) -> RabbitConfig:
    return RabbitConfig(url=amqp_url, queues=[queue_name], prefetch=4)


@pytest.fixture
def worker_config(node_id: str, queue_name: str) -> WorkerConfig:
    config = WorkerConfig(
        node_id=node_id,
        # Short lease so an abandoned job in a failing test doesn't linger for 90s.
        heartbeat_interval_seconds=2.0,
        lock_duration_seconds=6.0,
        shutdown_timeout_seconds=5.0,
        cancel_grace_seconds=2.0,
    )
    config.queue(queue_name, concurrency=4)
    return config


@pytest_asyncio.fixture
async def storage(scheduler_config: SchedulerConfig) -> AsyncIterator[Storage]:
    store = Storage(scheduler_config)
    await store.start()
    try:
        yield store
    finally:
        await store.close()


@pytest_asyncio.fixture
async def publisher(rabbit_config: RabbitConfig) -> AsyncIterator[RabbitTransport]:
    """A transport used only to publish, standing outside the worker under test."""
    transport = RabbitTransport(rabbit_config)
    await transport.start()
    try:
        yield transport
    finally:
        await transport.close()


@pytest_asyncio.fixture
async def outbox_pump(
    storage: Storage, publisher: RabbitTransport, queue_name: str
) -> AsyncIterator[None]:
    """Stand-in for the infra leader's outbox publisher.

    In production the Kotlin ``scheduler-infra`` process drains the outbox into RabbitMQ, so
    without it a retry (which enqueues a *delayed* outbox row rather than republishing
    itself) would never come back. This loop does the same job, scoped to one test's queue.

    Request it from any test that expects a job to be delivered more than once — retries,
    DAG promotions, paused-type redelivery.
    """

    async def pump() -> None:
        while True:
            try:
                async with storage.connection() as conn:
                    cur = await conn.execute(
                        """
                        SELECT id, job_id, routing_key, priority, delay_ms
                        FROM outbox
                        WHERE published_at IS NULL AND routing_key = %s
                        ORDER BY id LIMIT 100
                        """,
                        (queue_name,),
                    )
                    rows = list(await cur.fetchall())
                for row in rows:
                    # publish then mark, exactly like PublishOutboxBatchUseCase — a crash in
                    # between costs a duplicate delivery, which pickup filters out.
                    await publisher.publish(
                        row["job_id"], row["routing_key"], row["priority"], row["delay_ms"]
                    )
                    async with storage.connection() as conn:
                        await conn.execute(
                            "UPDATE outbox SET published_at = now() WHERE id = %s", (row["id"],)
                        )
            except asyncio.CancelledError:
                raise
            except Exception:  # a blip must not kill the pump mid-test
                pass
            await asyncio.sleep(0.1)

    task = asyncio.create_task(pump())
    try:
        yield
    finally:
        task.cancel()
        with contextlib.suppress(asyncio.CancelledError):
            await task


@pytest_asyncio.fixture
async def scheduler(
    scheduler_config: SchedulerConfig, storage: Storage, queue_name: str
) -> AsyncIterator[Scheduler]:
    instance = Scheduler(scheduler_config, storage=storage)
    await instance.start()
    try:
        yield instance
    finally:
        # Clean up everything this test created; children cascade with their parents.
        async with storage.transaction() as conn:
            await conn.execute("DELETE FROM job WHERE queue = %s", (queue_name,))
            await conn.execute("DELETE FROM recurring_job WHERE queue = %s", (queue_name,))
        await instance.close()
