"""Postgres producer/worker expiry contract, with direct delivery and no Rabbit server."""

from __future__ import annotations

import uuid
from dataclasses import dataclass
from datetime import datetime, timedelta, timezone
from unittest.mock import MagicMock

import pytest

from taskscheduler import (
    HandlerRegistry,
    JobState,
    OnFailure,
    RabbitConfig,
    Scheduler,
    SchedulerConfig,
    Storage,
    WorkerConfig,
    WorkerPool,
    job_type,
)
from taskscheduler.transport import RabbitTransport

pytestmark = pytest.mark.asyncio


@job_type
@dataclass
class Work:
    pass


def worker_for(storage: Storage, config: SchedulerConfig, queue: str):
    registry = HandlerRegistry()
    calls = []

    @registry.handler(Work)
    async def handle(ctx, job):
        calls.append(ctx.job_id)

    worker = WorkerPool(
        scheduler_config=config,
        worker_config=WorkerConfig(node_id=config.node_id).queue(queue, concurrency=1),
        rabbit_config=RabbitConfig(url="amqp://unused/"),
        registry=registry,
        storage=storage,
        transport=MagicMock(spec=RabbitTransport),
    )
    worker._running = True
    return worker, calls


async def row_for(storage: Storage, job_id: uuid.UUID):
    async with storage.connection() as conn:
        row = await storage.find_by_id(conn, job_id)
    assert row is not None
    return row


async def test_expired_pickup_cancels_without_calling_the_handler_and_resolves_deps(
    scheduler: Scheduler, storage: Storage, scheduler_config: SchedulerConfig, queue_name: str
):
    worker, calls = worker_for(storage, scheduler_config, queue_name)
    at = datetime.now(timezone.utc) - timedelta(minutes=1)
    parent = await scheduler.schedule_at(
        Work(), at, queue=queue_name, max_start_delay_seconds=10
    )
    child = await scheduler.enqueue_after(
        Work(), wait_for=[parent], queue=queue_name, on_parent_failure=OnFailure.CANCEL_CHILD
    )

    await worker._process_one(parent, queue_name)

    row = await row_for(storage, parent)
    assert row.state is JobState.CANCELLED
    assert row.start_deadline == at + timedelta(seconds=10)
    assert (await row_for(storage, child)).state is JobState.CANCELLED
    assert calls == []


@pytest.mark.parametrize("delay", [None, 120])
async def test_missing_deadline_and_allowed_lateness_still_execute(
    scheduler: Scheduler, storage: Storage, scheduler_config: SchedulerConfig,
    queue_name: str, delay
):
    worker, calls = worker_for(storage, scheduler_config, queue_name)
    job_id = await scheduler.schedule_at(
        Work(), datetime.now(timezone.utc) - timedelta(minutes=1),
        queue=queue_name, max_start_delay_seconds=delay,
    )

    await worker._process_one(job_id, queue_name)

    assert (await row_for(storage, job_id)).state is JobState.SUCCEEDED
    assert calls == [job_id]


async def test_retry_keeps_the_original_deadline(
    scheduler: Scheduler, storage: Storage, scheduler_config: SchedulerConfig, queue_name: str
):
    worker, calls = worker_for(storage, scheduler_config, queue_name)
    job_id = await scheduler.enqueue(Work(), queue=queue_name, max_start_delay_seconds=0)
    original = await row_for(storage, job_id)
    # Simulate a prior failed attempt without waiting for a real timeout/backoff.
    async with storage.transaction() as conn:
        await storage.pickup(conn, job_id, scheduler_config.node_id, 90)
        picked = await storage.find_by_id(conn, job_id)
        assert picked is not None
        await storage.mark_for_retry(
            conn, job_id=job_id, expected_version=picked.version, backoff_seconds=1,
            error_msg="retry", error_stack=None,
        )

    await worker._process_one(job_id, queue_name)

    row = await row_for(storage, job_id)
    assert row.state is JobState.CANCELLED
    assert row.start_deadline == original.start_deadline
    assert calls == []


async def test_future_schedule_counts_the_delay_from_the_scheduled_time(
    scheduler: Scheduler, storage: Storage, queue_name: str
):
    at = datetime.now(timezone.utc) + timedelta(days=30)

    job_id = await scheduler.schedule_at(
        Work(), at, queue=queue_name, max_start_delay_seconds=0.025
    )

    row = await row_for(storage, job_id)
    assert row.state is JobState.SCHEDULED
    assert row.start_deadline == at + timedelta(milliseconds=25)


async def test_recurring_start_delay_can_be_updated_or_disabled(
    scheduler: Scheduler, storage: Storage, queue_name: str
):
    recurring_id = f"start-delay-{uuid.uuid4()}"

    for delay, expected_ms in [(30, 30_000), (0, 0), (None, None)]:
        await scheduler.recurring(
            recurring_id, "* * * * *", Work(), queue=queue_name, max_start_delay_seconds=delay
        )
        async with storage.connection() as conn:
            cursor = await conn.execute(
                "SELECT max_start_delay_ms FROM recurring_job WHERE id = %s", (recurring_id,)
            )
            row = await cursor.fetchone()
        assert row["max_start_delay_ms"] == expected_ms
