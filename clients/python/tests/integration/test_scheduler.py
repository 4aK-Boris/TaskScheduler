"""Producer-side behaviour against a real Postgres.

These assert on the rows themselves, because the rows *are* the contract: the Kotlin infra
process and dashboard read exactly these columns.
"""

from __future__ import annotations

import uuid
from dataclasses import dataclass
from datetime import datetime, timedelta, timezone

import pytest

from taskscheduler import ConcurrencyPolicy, JobState, OnFailure, Scheduler, Storage, job_type

pytestmark = pytest.mark.asyncio


@job_type
@dataclass
class Sample:
    n: int
    label: str = "x"


async def _job(storage: Storage, job_id: uuid.UUID) -> dict:
    async with storage.connection() as conn:
        cur = await conn.execute("SELECT * FROM job WHERE id = %s", (job_id,))
        row = await cur.fetchone()
    assert row is not None, f"job {job_id} was not written"
    return row


async def _outbox(storage: Storage, job_id: uuid.UUID) -> list[dict]:
    async with storage.connection() as conn:
        cur = await conn.execute(
            "SELECT * FROM outbox WHERE job_id = %s ORDER BY id", (job_id,)
        )
        return list(await cur.fetchall())


async def _events(storage: Storage, job_id: uuid.UUID) -> list[str]:
    async with storage.connection() as conn:
        cur = await conn.execute(
            "SELECT event_type FROM job_event WHERE job_id = %s ORDER BY id", (job_id,)
        )
        return [r["event_type"] for r in await cur.fetchall()]


async def test_enqueue_writes_job_outbox_and_event(
    scheduler: Scheduler, storage: Storage, queue_name: str
):
    job_id = await scheduler.enqueue(Sample(n=1), queue=queue_name, priority=5)

    row = await _job(storage, job_id)
    assert row["state"] == JobState.ENQUEUED.value
    assert row["queue"] == queue_name
    assert row["priority"] == 5
    assert row["payload_type"].endswith(".Sample")
    assert row["payload_json"] == {"n": 1, "label": "x"}
    assert row["attempts"] == 0
    assert row["version"] == 0
    assert row["pending_deps"] == 0

    outbox = await _outbox(storage, job_id)
    assert len(outbox) == 1
    assert outbox[0]["routing_key"] == queue_name
    assert outbox[0]["delay_ms"] == 0
    assert outbox[0]["published_at"] is None  # infra publishes it, not us

    assert await _events(storage, job_id) == ["CREATED"]


async def test_priority_is_clamped_to_the_allowed_range(
    scheduler: Scheduler, storage: Storage, queue_name: str
):
    """The column has a CHECK constraint — clamping keeps enqueue from failing outright."""
    job_id = await scheduler.enqueue(Sample(n=1), queue=queue_name, priority=99)
    assert (await _job(storage, job_id))["priority"] == 10


async def test_target_node_sets_the_routing_key(
    scheduler: Scheduler, storage: Storage, queue_name: str
):
    job_id = await scheduler.enqueue(Sample(n=1), queue=queue_name, target_node="gpu-1")
    assert (await _outbox(storage, job_id))[0]["routing_key"] == "node.gpu-1"
    assert (await _job(storage, job_id))["target_node"] == "gpu-1"


async def test_near_future_schedule_uses_a_delayed_outbox_row(
    scheduler: Scheduler, storage: Storage, queue_name: str
):
    at = datetime.now(timezone.utc) + timedelta(minutes=5)
    job_id = await scheduler.schedule_at(Sample(n=1), at, queue=queue_name)

    assert (await _job(storage, job_id))["state"] == JobState.ENQUEUED.value
    delay_ms = (await _outbox(storage, job_id))[0]["delay_ms"]
    assert 4 * 60_000 < delay_ms <= 5 * 60_000


async def test_far_future_schedule_is_parked_without_an_outbox_row(
    scheduler: Scheduler, storage: Storage, queue_name: str
):
    """Beyond the fast-forward window the broker must not hold the message — infra promotes it."""
    at = datetime.now(timezone.utc) + timedelta(days=30)
    job_id = await scheduler.schedule_at(Sample(n=1), at, queue=queue_name)

    assert (await _job(storage, job_id))["state"] == JobState.SCHEDULED.value
    assert await _outbox(storage, job_id) == []


async def test_enqueue_once_skip_coalesces(scheduler: Scheduler, queue_name: str):
    key = f"key-{uuid.uuid4().hex[:8]}"
    first = await scheduler.enqueue_once(key, Sample(n=1), queue=queue_name)
    second = await scheduler.enqueue_once(key, Sample(n=2), queue=queue_name)
    assert first == second


async def test_enqueue_once_after_parks_a_successor(
    scheduler: Scheduler, storage: Storage, queue_name: str
):
    key = f"key-{uuid.uuid4().hex[:8]}"
    leader = await scheduler.enqueue_once(key, Sample(n=1), queue=queue_name)
    successor = await scheduler.enqueue_once(
        key, Sample(n=2), queue=queue_name, policy=ConcurrencyPolicy.ENQUEUE_AFTER
    )

    assert successor != leader
    row = await _job(storage, successor)
    assert row["state"] == JobState.AWAITING_DEPS.value
    assert row["pending_deps"] == 1
    assert await _outbox(storage, successor) == []  # waits for the leader to finish


async def test_chain_links_each_step_to_the_previous(
    scheduler: Scheduler, storage: Storage, queue_name: str
):
    ids = await scheduler.chain(Sample(n=1), Sample(n=2), Sample(n=3), queue=queue_name)
    assert len(ids) == 3

    assert (await _job(storage, ids[0]))["state"] == JobState.ENQUEUED.value
    for step in ids[1:]:
        row = await _job(storage, step)
        assert row["state"] == JobState.AWAITING_DEPS.value
        assert row["pending_deps"] == 1

    async with storage.connection() as conn:
        cur = await conn.execute(
            "SELECT parent_id, child_id FROM job_dependency WHERE child_id = ANY(%s)", (ids[1:],)
        )
        edges = {(r["parent_id"], r["child_id"]) for r in await cur.fetchall()}
    assert edges == {(ids[0], ids[1]), (ids[1], ids[2])}


async def test_barrier_waits_for_every_parent(
    scheduler: Scheduler, storage: Storage, queue_name: str
):
    a = await scheduler.enqueue(Sample(n=1), queue=queue_name)
    b = await scheduler.enqueue(Sample(n=2), queue=queue_name)
    child = await scheduler.enqueue_after(Sample(n=3), wait_for=[a, b], queue=queue_name)

    row = await _job(storage, child)
    assert row["state"] == JobState.AWAITING_DEPS.value
    assert row["pending_deps"] == 2
    assert row["initial_pending_deps"] == 2


async def test_barrier_on_already_finished_parents_enqueues_immediately(
    scheduler: Scheduler, storage: Storage, queue_name: str
):
    """Without this, a parent that finishes mid-registration would strand the child forever."""
    parent = await scheduler.enqueue(Sample(n=1), queue=queue_name)
    async with storage.transaction() as conn:
        await conn.execute(
            "UPDATE job SET state = 'SUCCEEDED', version = version + 1 WHERE id = %s", (parent,)
        )

    child = await scheduler.enqueue_after(Sample(n=2), wait_for=[parent], queue=queue_name)
    assert (await _job(storage, child))["state"] == JobState.ENQUEUED.value
    assert len(await _outbox(storage, child)) == 1


async def test_barrier_on_a_failed_parent_propagates(
    scheduler: Scheduler, storage: Storage, queue_name: str
):
    parent = await scheduler.enqueue(Sample(n=1), queue=queue_name)
    async with storage.transaction() as conn:
        await conn.execute(
            "UPDATE job SET state = 'FAILED', version = version + 1 WHERE id = %s", (parent,)
        )

    child = await scheduler.enqueue_after(Sample(n=2), wait_for=[parent], queue=queue_name)
    assert (await _job(storage, child))["state"] == JobState.FAILED.value


async def test_barrier_with_ignore_runs_despite_a_failed_parent(
    scheduler: Scheduler, storage: Storage, queue_name: str
):
    parent = await scheduler.enqueue(Sample(n=1), queue=queue_name)
    async with storage.transaction() as conn:
        await conn.execute(
            "UPDATE job SET state = 'FAILED', version = version + 1 WHERE id = %s", (parent,)
        )

    child = await scheduler.enqueue_after(
        Sample(n=2), wait_for=[parent], on_parent_failure=OnFailure.IGNORE, queue=queue_name
    )
    assert (await _job(storage, child))["state"] == JobState.ENQUEUED.value


async def test_cancel_of_a_pending_job_is_immediate(
    scheduler: Scheduler, storage: Storage, queue_name: str
):
    job_id = await scheduler.enqueue(Sample(n=1), queue=queue_name)
    assert await scheduler.cancel(job_id, by="tester") == "CANCELLED"

    assert (await _job(storage, job_id))["state"] == JobState.CANCELLED.value
    assert "MANUAL_CANCELLED" in await _events(storage, job_id)


async def test_cancelling_a_running_job_only_requests_it(
    scheduler: Scheduler, storage: Storage, queue_name: str
):
    """A running handler can't be stopped from outside — it is flagged and signalled."""
    job_id = await scheduler.enqueue(Sample(n=1), queue=queue_name)
    async with storage.transaction() as conn:
        await conn.execute(
            "UPDATE job SET state = 'PROCESSING', version = version + 1 WHERE id = %s", (job_id,)
        )

    assert await scheduler.cancel(job_id, by="tester") == "CANCEL_REQUESTED"
    row = await _job(storage, job_id)
    assert row["state"] == JobState.PROCESSING.value
    assert row["cancel_requested_at"] is not None
    assert row["cancel_requested_by"] == "tester"


async def test_cancel_of_a_finished_job_is_rejected(
    scheduler: Scheduler, storage: Storage, queue_name: str
):
    job_id = await scheduler.enqueue(Sample(n=1), queue=queue_name)
    async with storage.transaction() as conn:
        await conn.execute("UPDATE job SET state = 'SUCCEEDED' WHERE id = %s", (job_id,))
    assert await scheduler.cancel(job_id) == "ALREADY_TERMINAL"


async def test_manual_retry_revives_a_failed_job(
    scheduler: Scheduler, storage: Storage, queue_name: str
):
    job_id = await scheduler.enqueue(Sample(n=1), queue=queue_name)
    async with storage.transaction() as conn:
        await conn.execute(
            "UPDATE job SET state = 'FAILED', attempts = 3, version = version + 1 WHERE id = %s",
            (job_id,),
        )

    assert await scheduler.retry(job_id, by="tester") == "RETRIED"
    row = await _job(storage, job_id)
    assert row["state"] == JobState.ENQUEUED.value
    assert row["attempts"] == 0  # fresh budget
    assert len(await _outbox(storage, job_id)) == 2  # original + the retry dispatch


async def test_manual_retry_once_grants_a_single_extra_attempt(
    scheduler: Scheduler, storage: Storage, queue_name: str
):
    job_id = await scheduler.enqueue(Sample(n=1), queue=queue_name)
    async with storage.transaction() as conn:
        await conn.execute(
            "UPDATE job SET state = 'FAILED', attempts = 3, version = version + 1 WHERE id = %s",
            (job_id,),
        )

    assert await scheduler.retry(job_id, fresh_budget=False) == "RETRIED"
    row = await _job(storage, job_id)
    assert row["attempts"] == 3
    assert row["max_attempts"] == 4


async def test_retrying_a_live_job_is_rejected(scheduler: Scheduler, queue_name: str):
    job_id = await scheduler.enqueue(Sample(n=1), queue=queue_name)
    assert await scheduler.retry(job_id) == "NOT_FAILED"


async def test_delete_requires_a_terminal_job(
    scheduler: Scheduler, storage: Storage, queue_name: str
):
    job_id = await scheduler.enqueue(Sample(n=1), queue=queue_name)
    assert await scheduler.delete(job_id) == "NOT_TERMINAL"

    async with storage.transaction() as conn:
        await conn.execute("UPDATE job SET state = 'SUCCEEDED' WHERE id = %s", (job_id,))
    assert await scheduler.delete(job_id) == "DELETED"

    async with storage.connection() as conn:
        cur = await conn.execute("SELECT 1 FROM job WHERE id = %s", (job_id,))
        assert await cur.fetchone() is None


async def test_unknown_job_ids_are_reported_not_raised(scheduler: Scheduler):
    missing = uuid.uuid4()
    assert await scheduler.cancel(missing) == "NOT_FOUND"
    assert await scheduler.retry(missing) == "NOT_FOUND"
    assert await scheduler.delete(missing) == "NOT_FOUND"


async def test_recurring_registration_is_idempotent(
    scheduler: Scheduler, storage: Storage, queue_name: str
):
    recurring_id = f"pytest-{uuid.uuid4().hex[:8]}"
    await scheduler.recurring(recurring_id, "0 3 * * *", Sample(n=1), queue=queue_name)
    await scheduler.recurring(recurring_id, "0 4 * * *", Sample(n=2), queue=queue_name)

    async with storage.connection() as conn:
        cur = await conn.execute(
            "SELECT * FROM recurring_job WHERE id = %s", (recurring_id,)
        )
        rows = list(await cur.fetchall())
    assert len(rows) == 1
    assert rows[0]["cron"] == "0 4 * * *"
    assert rows[0]["payload_json"] == {"n": 2, "label": "x"}
    assert rows[0]["enabled"] is True
    assert rows[0]["next_trigger_at"] > datetime.now(timezone.utc)
