"""End-to-end worker behaviour against a real Postgres and RabbitMQ.

No Kotlin process is needed: tests either publish a dispatch by hand or request the
``outbox_pump`` fixture, which replays what the infra leader does. Either way the real broker
path is exercised, including the delayed exchange that carries retry backoff.
"""

from __future__ import annotations

import asyncio
import uuid
from dataclasses import dataclass

import pytest

from taskscheduler import (
    HandlerRegistry,
    JobCancellationError,
    JobContext,
    JobState,
    NonRetriableError,
    RabbitConfig,
    RabbitTransport,
    Scheduler,
    SchedulerConfig,
    Storage,
    WorkerConfig,
    WorkerPool,
    job_type,
)
from taskscheduler.retry import FixedDelay

pytestmark = pytest.mark.asyncio

POLL_TIMEOUT = 25.0


@job_type
@dataclass
class Work:
    n: int


@job_type
@dataclass
class Unhandled:
    n: int


async def wait_for_state(
    storage: Storage, job_id: uuid.UUID, *states: JobState, timeout: float = POLL_TIMEOUT
) -> dict:
    """Poll until the row reaches one of ``states``; fail with the state it got stuck in."""
    wanted = {s.value for s in states}
    deadline = asyncio.get_running_loop().time() + timeout
    last: dict | None = None
    while asyncio.get_running_loop().time() < deadline:
        async with storage.connection() as conn:
            cur = await conn.execute("SELECT * FROM job WHERE id = %s", (job_id,))
            last = await cur.fetchone()
        if last is not None and last["state"] in wanted:
            return last
        await asyncio.sleep(0.1)
    got = last["state"] if last else "<no row>"
    raise AssertionError(f"job {job_id} stayed in {got}, expected one of {sorted(wanted)}")


async def dispatch(publisher: RabbitTransport, scheduler: Scheduler, payload, **options):
    """Enqueue a job and hand it to the broker, standing in for the infra outbox publisher."""
    job_id = await scheduler.enqueue(payload, **options)
    await publisher.publish(job_id, routing_key=options.get("queue", "default"))
    return job_id


async def run_worker(
    scheduler_config: SchedulerConfig,
    worker_config: WorkerConfig,
    rabbit_config: RabbitConfig,
    registry: HandlerRegistry,
    storage: Storage,
) -> WorkerPool:
    pool = WorkerPool(
        scheduler_config=scheduler_config,
        worker_config=worker_config,
        rabbit_config=rabbit_config,
        registry=registry,
        storage=storage,
    )
    await pool.start()
    return pool


async def test_successful_job_reaches_succeeded(
    scheduler, scheduler_config, worker_config, rabbit_config, storage, publisher, queue_name
):
    seen: list[int] = []
    registry = HandlerRegistry()

    @registry.handler(Work)
    async def handle(ctx: JobContext, job: Work) -> None:
        seen.append(job.n)

    pool = await run_worker(scheduler_config, worker_config, rabbit_config, registry, storage)
    try:
        job_id = await dispatch(publisher, scheduler, Work(n=7), queue=queue_name)
        row = await wait_for_state(storage, job_id, JobState.SUCCEEDED)
    finally:
        await pool.stop()

    assert seen == [7]
    assert row["attempts"] == 1
    assert row["locked_by"] is None
    assert row["locked_until"] is None
    assert row["duration_ms"] is not None

    async with storage.connection() as conn:
        cur = await conn.execute(
            "SELECT event_type FROM job_event WHERE job_id = %s ORDER BY id", (job_id,)
        )
        events = [r["event_type"] for r in await cur.fetchall()]
    assert events == ["CREATED", "PICKED_UP", "SUCCEEDED"]


async def test_failure_is_retried_then_fails_terminally(
    scheduler, scheduler_config, worker_config, rabbit_config, storage, outbox_pump, queue_name
):
    attempts: list[int] = []
    registry = HandlerRegistry()

    @registry.handler(Work, retry_policy=FixedDelay(max_attempts=2, delay_seconds=0.2))
    async def handle(ctx: JobContext, job: Work) -> None:
        attempts.append(ctx.attempt)
        raise RuntimeError("boom")

    pool = await run_worker(scheduler_config, worker_config, rabbit_config, registry, storage)
    try:
        job_id = await scheduler.enqueue(Work(n=1), queue=queue_name, max_attempts=2)
        row = await wait_for_state(storage, job_id, JobState.FAILED)
    finally:
        await pool.stop()

    assert attempts == [1, 2], "the retry should have been redelivered by the delayed exchange"
    assert row["attempts"] == 2

    async with storage.connection() as conn:
        cur = await conn.execute(
            """
            SELECT event_type, prev_state, error_msg FROM job_event
            WHERE job_id = %s ORDER BY id
            """,
            (job_id,),
        )
        events = list(await cur.fetchall())
    types = [e["event_type"] for e in events]
    assert types == ["CREATED", "PICKED_UP", "RETRY", "PICKED_UP", "FAILED"]
    assert "boom" in events[-1]["error_msg"]
    # The timeline must show where each pickup came from: a fresh job from ENQUEUED, the
    # second attempt from AWAITING_RETRY.
    pickups = [e["prev_state"] for e in events if e["event_type"] == "PICKED_UP"]
    assert pickups == ["ENQUEUED", "AWAITING_RETRY"]


async def test_non_retriable_error_skips_the_remaining_budget(
    scheduler, scheduler_config, worker_config, rabbit_config, storage, publisher, queue_name
):
    calls: list[int] = []
    registry = HandlerRegistry()

    @registry.handler(Work)
    async def handle(ctx: JobContext, job: Work) -> None:
        calls.append(ctx.attempt)
        raise NonRetriableError("bad input")

    pool = await run_worker(scheduler_config, worker_config, rabbit_config, registry, storage)
    try:
        job_id = await dispatch(publisher, scheduler, Work(n=1), queue=queue_name, max_attempts=5)
        row = await wait_for_state(storage, job_id, JobState.FAILED)
    finally:
        await pool.stop()

    assert calls == [1], "a non-retriable failure must not consume the rest of the budget"
    assert row["attempts"] == 1


async def test_final_failure_hook_runs_once(
    scheduler, scheduler_config, worker_config, rabbit_config, storage, publisher, queue_name
):
    hook_calls: list[str] = []
    registry = HandlerRegistry()

    async def on_final(ctx: JobContext, job: Work, error: BaseException) -> None:
        hook_calls.append(str(error))

    @registry.handler(Work, on_final_failure=on_final)
    async def handle(ctx: JobContext, job: Work) -> None:
        raise NonRetriableError("done for")

    pool = await run_worker(scheduler_config, worker_config, rabbit_config, registry, storage)
    try:
        job_id = await dispatch(publisher, scheduler, Work(n=1), queue=queue_name)
        await wait_for_state(storage, job_id, JobState.FAILED)
        await asyncio.sleep(0.3)  # the hook runs just after the transition
    finally:
        await pool.stop()

    assert hook_calls == ["done for"]


async def test_unregistered_type_fails_rather_than_bouncing(
    scheduler, scheduler_config, worker_config, rabbit_config, storage, publisher, queue_name
):
    """A misconfiguration should be visible on the dashboard, not an invisible hot loop."""
    registry = HandlerRegistry()

    @registry.handler(Work)
    async def handle(ctx: JobContext, job: Work) -> None: ...

    pool = await run_worker(scheduler_config, worker_config, rabbit_config, registry, storage)
    try:
        job_id = await dispatch(publisher, scheduler, Unhandled(n=1), queue=queue_name)
        row = await wait_for_state(storage, job_id, JobState.FAILED)
    finally:
        await pool.stop()

    async with storage.connection() as conn:
        cur = await conn.execute(
            "SELECT error_msg FROM job_event WHERE job_id = %s AND event_type = 'FAILED'",
            (job_id,),
        )
        error = (await cur.fetchone())["error_msg"]
    assert "no handler registered" in error
    assert row["attempts"] == 1


async def test_undecodable_payload_fails_without_retrying(
    scheduler, scheduler_config, worker_config, rabbit_config, storage, publisher, queue_name
):
    """The stored bytes will never change, so retrying is pointless."""
    calls: list[int] = []
    registry = HandlerRegistry()

    @registry.handler(Work)
    async def handle(ctx: JobContext, job: Work) -> None:
        calls.append(1)

    pool = await run_worker(scheduler_config, worker_config, rabbit_config, registry, storage)
    try:
        job_id = await scheduler.enqueue(Work(n=1), queue=queue_name, max_attempts=5)
        async with storage.transaction() as conn:
            await conn.execute(
                "UPDATE job SET payload_json = %s::jsonb WHERE id = %s",
                ('{"wrong_field": 1}', job_id),
            )
        await publisher.publish(job_id, routing_key=queue_name)
        row = await wait_for_state(storage, job_id, JobState.FAILED)
    finally:
        await pool.stop()

    assert calls == []
    assert row["attempts"] == 1


async def test_progress_is_persisted(
    scheduler, scheduler_config, worker_config, rabbit_config, storage, publisher, queue_name
):
    registry = HandlerRegistry()

    @registry.handler(Work)
    async def handle(ctx: JobContext, job: Work) -> None:
        bar = ctx.progress_bar(2)
        await bar.succeeded()
        await bar.succeeded()

    pool = await run_worker(scheduler_config, worker_config, rabbit_config, registry, storage)
    try:
        job_id = await dispatch(publisher, scheduler, Work(n=1), queue=queue_name)
        await wait_for_state(storage, job_id, JobState.SUCCEEDED)
    finally:
        await pool.stop()

    async with storage.connection() as conn:
        cur = await conn.execute(
            "SELECT progress, progress_succeeded, progress_total FROM job WHERE id = %s", (job_id,)
        )
        row = await cur.fetchone()
    # The final tick bypasses the throttle, so a fast loop still ends at 100%.
    assert row["progress"] == pytest.approx(1.0)
    assert row["progress_succeeded"] == 2
    assert row["progress_total"] == 2


async def test_cooperative_cancellation_ends_as_cancelled(
    scheduler, scheduler_config, worker_config, rabbit_config, storage, publisher, queue_name
):
    started = asyncio.Event()
    registry = HandlerRegistry()

    @registry.handler(Work)
    async def handle(ctx: JobContext, job: Work) -> None:
        started.set()
        for _ in range(200):
            if await ctx.is_cancellation_requested():
                raise JobCancellationError("stopped on request")
            await asyncio.sleep(0.05)

    pool = await run_worker(scheduler_config, worker_config, rabbit_config, registry, storage)
    try:
        job_id = await dispatch(publisher, scheduler, Work(n=1), queue=queue_name)
        await asyncio.wait_for(started.wait(), timeout=POLL_TIMEOUT)
        assert await scheduler.cancel(job_id, by="tester") == "CANCEL_REQUESTED"
        row = await wait_for_state(storage, job_id, JobState.CANCELLED)
    finally:
        await pool.stop()

    assert row["locked_by"] is None


async def test_timeout_is_enforced_per_job(
    scheduler, scheduler_config, worker_config, rabbit_config, storage, publisher, queue_name
):
    registry = HandlerRegistry()

    @registry.handler(Work, retry_policy=FixedDelay(max_attempts=1))
    async def handle(ctx: JobContext, job: Work) -> None:
        await asyncio.sleep(30)

    pool = await run_worker(scheduler_config, worker_config, rabbit_config, registry, storage)
    try:
        job_id = await dispatch(
            publisher, scheduler, Work(n=1), queue=queue_name, timeout_seconds=1, max_attempts=1
        )
        row = await wait_for_state(storage, job_id, JobState.FAILED)
    finally:
        await pool.stop()

    async with storage.connection() as conn:
        cur = await conn.execute(
            "SELECT error_msg FROM job_event WHERE job_id = %s AND event_type = 'FAILED'",
            (job_id,),
        )
        assert "timeout" in (await cur.fetchone())["error_msg"].lower()
    assert row["state"] == JobState.FAILED.value


async def test_lease_is_extended_while_a_job_runs(
    scheduler, scheduler_config, worker_config, rabbit_config, storage, publisher, queue_name
):
    """A job outliving one lease period must not be stolen by orphan recovery."""
    registry = HandlerRegistry()

    @registry.handler(Work)
    async def handle(ctx: JobContext, job: Work) -> None:
        await asyncio.sleep(worker_config.lock_duration_seconds + 3)

    pool = await run_worker(scheduler_config, worker_config, rabbit_config, registry, storage)
    try:
        job_id = await dispatch(publisher, scheduler, Work(n=1), queue=queue_name)
        await wait_for_state(storage, job_id, JobState.PROCESSING)
        await asyncio.sleep(worker_config.lock_duration_seconds + 1)

        async with storage.connection() as conn:
            cur = await conn.execute(
                "SELECT locked_until > now() AS still_held FROM job WHERE id = %s", (job_id,)
            )
            assert (await cur.fetchone())["still_held"] is True

        await wait_for_state(storage, job_id, JobState.SUCCEEDED)
    finally:
        await pool.stop()


async def test_dag_child_is_promoted_when_its_parent_succeeds(
    scheduler, scheduler_config, worker_config, rabbit_config, storage, outbox_pump, queue_name
):
    registry = HandlerRegistry()

    @registry.handler(Work)
    async def handle(ctx: JobContext, job: Work) -> None: ...

    pool = await run_worker(scheduler_config, worker_config, rabbit_config, registry, storage)
    try:
        parent = await scheduler.enqueue(Work(n=1), queue=queue_name)
        child = await scheduler.enqueue_after(Work(n=2), wait_for=[parent], queue=queue_name)

        await wait_for_state(storage, parent, JobState.SUCCEEDED)
        row = await wait_for_state(storage, child, JobState.ENQUEUED)
    finally:
        await pool.stop()

    assert row["pending_deps"] == 0
    async with storage.connection() as conn:
        cur = await conn.execute("SELECT count(*) AS n FROM outbox WHERE job_id = %s", (child,))
        # The promotion must leave a dispatch behind, or the child never runs.
        assert (await cur.fetchone())["n"] == 1


async def test_dag_child_fails_when_its_parent_fails(
    scheduler, scheduler_config, worker_config, rabbit_config, storage, outbox_pump, queue_name
):
    registry = HandlerRegistry()

    @registry.handler(Work, retry_policy=FixedDelay(max_attempts=1))
    async def handle(ctx: JobContext, job: Work) -> None:
        raise RuntimeError("parent exploded")

    pool = await run_worker(scheduler_config, worker_config, rabbit_config, registry, storage)
    try:
        parent = await scheduler.enqueue(Work(n=1), queue=queue_name, max_attempts=1)
        child = await scheduler.enqueue_after(Work(n=2), wait_for=[parent], queue=queue_name)

        await wait_for_state(storage, parent, JobState.FAILED)
        await wait_for_state(storage, child, JobState.FAILED)
    finally:
        await pool.stop()


async def test_paused_type_is_returned_unrun(
    scheduler, scheduler_config, worker_config, rabbit_config, storage, outbox_pump, queue_name
):
    """Pausing a type from the dashboard must not consume attempts or fail jobs."""
    calls: list[int] = []
    registry = HandlerRegistry()

    @registry.handler(Work)
    async def handle(ctx: JobContext, job: Work) -> None:
        calls.append(1)

    payload_type = f"{Work.__module__}.Work"
    async with storage.transaction() as conn:
        await conn.execute(
            """
            INSERT INTO job_type_pause (payload_type, paused_since, paused_by)
            VALUES (%s, now(), 'tester')
            ON CONFLICT (payload_type) DO NOTHING
            """,
            (payload_type,),
        )

    pool = await run_worker(scheduler_config, worker_config, rabbit_config, registry, storage)
    try:
        job_id = await scheduler.enqueue(Work(n=1), queue=queue_name)
        row = await wait_for_state(storage, job_id, JobState.ENQUEUED, timeout=15.0)
        assert calls == []
        assert row["attempts"] == 0, "a paused job must not burn an attempt"
    finally:
        await pool.stop()
        async with storage.transaction() as conn:
            await conn.execute(
                "DELETE FROM job_type_pause WHERE payload_type = %s", (payload_type,)
            )


async def test_stale_delivery_for_a_finished_job_is_ignored(
    scheduler, scheduler_config, worker_config, rabbit_config, storage, publisher, queue_name
):
    """Redelivery after the job already finished must not run it a second time."""
    calls: list[int] = []
    registry = HandlerRegistry()

    @registry.handler(Work)
    async def handle(ctx: JobContext, job: Work) -> None:
        calls.append(1)

    pool = await run_worker(scheduler_config, worker_config, rabbit_config, registry, storage)
    try:
        job_id = await scheduler.enqueue(Work(n=1), queue=queue_name)
        async with storage.transaction() as conn:
            await conn.execute(
                "UPDATE job SET state = 'SUCCEEDED', version = version + 1 WHERE id = %s",
                (job_id,),
            )
        await publisher.publish(job_id, routing_key=queue_name)
        await asyncio.sleep(2.0)
    finally:
        await pool.stop()

    assert calls == []


async def test_worker_registers_itself_for_the_dashboard(
    scheduler_config, worker_config, rabbit_config, storage, node_id
):
    registry = HandlerRegistry()

    @registry.handler(Work)
    async def handle(ctx: JobContext, job: Work) -> None: ...

    worker_config.node_tags = ["pytest", "eu-west"]
    pool = await run_worker(scheduler_config, worker_config, rabbit_config, registry, storage)
    try:
        await asyncio.sleep(1.0)
        async with storage.connection() as conn:
            cur = await conn.execute("SELECT * FROM worker WHERE node_id = %s", (node_id,))
            row = await cur.fetchone()
        assert row is not None
        assert row["tags"] == ["pytest", "eu-west"]
    finally:
        await pool.stop()

    # Shutdown deregisters the node so the dashboard doesn't show a ghost worker.
    async with storage.connection() as conn:
        cur = await conn.execute("SELECT 1 FROM worker WHERE node_id = %s", (node_id,))
        assert await cur.fetchone() is None
