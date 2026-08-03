"""Configuration guard rails, routing keys, and handler registration."""

from __future__ import annotations

import uuid
from dataclasses import dataclass

import pytest

from taskscheduler import (
    ConfigurationError,
    HandlerRegistry,
    JobContext,
    JobHandler,
    JobRow,
    JobState,
    RabbitConfig,
    SchedulerConfig,
    WorkerConfig,
    dsn_from_jdbc,
    job_type,
)
from taskscheduler.events import EventEncoder


@job_type
@dataclass
class Ping:
    n: int


def _row(**overrides) -> JobRow:
    base = dict(
        id=uuid.uuid4(),
        state=JobState.ENQUEUED,
        queue="default",
        priority=0,
        payload_type="tests.Ping",
        payload_json="{}",
        attempts=1,
        max_attempts=3,
        version=1,
        pending_deps=0,
    )
    base.update(overrides)
    return JobRow(**base)


# --- routing key -----------------------------------------------------------------------


def test_routing_key_defaults_to_the_queue():
    assert _row(queue="billing").routing_key == "billing"


def test_target_node_wins_over_tag_and_queue():
    row = _row(queue="billing", target_node="gpu-1", target_tag="gpu")
    assert row.routing_key == "node.gpu-1"


def test_target_tag_wins_over_queue():
    assert _row(queue="billing", target_tag="gpu").routing_key == "tag.gpu"


# --- job state -------------------------------------------------------------------------


@pytest.mark.parametrize(
    "state,terminal",
    [
        (JobState.SUCCEEDED, True),
        (JobState.FAILED, True),
        (JobState.CANCELLED, True),
        (JobState.ENQUEUED, False),
        (JobState.PROCESSING, False),
        (JobState.AWAITING_RETRY, False),
        (JobState.AWAITING_DEPS, False),
        (JobState.SCHEDULED, False),
    ],
)
def test_terminal_states(state, terminal):
    assert state.is_terminal is terminal


# --- configuration ---------------------------------------------------------------------


def test_dsn_requires_a_value():
    with pytest.raises(ConfigurationError):
        SchedulerConfig(dsn="")


def test_default_retry_policy_is_filled_in():
    config = SchedulerConfig(dsn="postgresql://localhost/x", default_max_attempts=7)
    assert config.default_retry_policy is not None
    assert config.default_retry_policy.max_attempts == 7


def test_worker_without_queues_is_rejected():
    with pytest.raises(ConfigurationError, match="no queues"):
        WorkerConfig().validate()


def test_duplicate_queue_names_are_rejected():
    config = WorkerConfig().queue("a").queue("a")
    with pytest.raises(ConfigurationError, match="duplicate queue"):
        config.validate()


def test_heartbeat_must_leave_room_for_a_missed_tick():
    """heartbeat > lock/3 means one hiccup lets another node steal a running job."""
    config = WorkerConfig(heartbeat_interval_seconds=60, lock_duration_seconds=90).queue("a")
    with pytest.raises(ConfigurationError, match="at most a third"):
        config.validate()


def test_default_timings_are_valid():
    WorkerConfig().queue("default").validate()


def test_prefetch_defaults_to_concurrency():
    config = WorkerConfig().queue("a", concurrency=4)
    assert config.queues[0].prefetch == 4


def test_priority_outside_range_is_rejected():
    with pytest.raises(ConfigurationError):
        WorkerConfig().queue("a", default_priority=11)


def test_rabbit_prefetch_must_be_positive():
    with pytest.raises(ConfigurationError):
        RabbitConfig(prefetch=0)


def test_jdbc_url_conversion():
    assert (
        dsn_from_jdbc("jdbc:postgresql://db:5432/scheduler", "u", "p")
        == "postgresql://u:p@db:5432/scheduler"
    )


def test_non_jdbc_url_is_rejected():
    with pytest.raises(ConfigurationError):
        dsn_from_jdbc("postgresql://db/scheduler", "u", "p")


# --- registry --------------------------------------------------------------------------


def test_function_handler_is_registered_under_the_payload_type():
    registry = HandlerRegistry()

    @registry.handler(Ping)
    async def handle(ctx: JobContext, job: Ping) -> None: ...

    entry = registry.find(f"{Ping.__module__}.Ping")
    assert entry is not None
    assert entry.payload_cls is Ping
    assert len(registry) == 1


def test_sync_handler_is_rejected():
    registry = HandlerRegistry()
    with pytest.raises(ConfigurationError, match="async def"):

        @registry.handler(Ping)
        def handle(ctx, job): ...


def test_two_handlers_for_one_type_are_rejected():
    registry = HandlerRegistry()

    @registry.handler(Ping)
    async def first(ctx, job): ...

    with pytest.raises(ConfigurationError, match="two handlers"):

        @registry.handler(Ping)
        async def second(ctx, job): ...


def test_class_handler_requires_a_payload_attribute():
    class Incomplete(JobHandler):
        async def execute(self, ctx, job): ...

    with pytest.raises(ConfigurationError, match="payload"):
        HandlerRegistry().register(Incomplete())


def test_class_handler_carries_its_retry_policy():
    from taskscheduler import FixedDelay

    class PingHandler(JobHandler[Ping]):
        payload = Ping
        retry_policy = FixedDelay(max_attempts=9)

        async def execute(self, ctx, job): ...

    registry = HandlerRegistry()
    registry.register(PingHandler())
    entry = registry.find(f"{Ping.__module__}.Ping")
    assert entry.retry_policy.max_attempts == 9


def test_unknown_type_resolves_to_nothing():
    assert HandlerRegistry().find("nope.Nope") is None


# --- dashboard events ------------------------------------------------------------------


def test_event_envelope_carries_origin_and_discriminator():
    import json

    payload = EventEncoder("node-1").job_state(
        uuid.uuid4(), JobState.PROCESSING, JobState.SUCCEEDED, "billing"
    )
    envelope = json.loads(payload)
    assert envelope["origin"] == "node-1"
    assert envelope["event"]["_type"] == "job_state"
    assert envelope["event"]["from"] == "PROCESSING"
    assert envelope["event"]["to"] == "SUCCEEDED"
    assert envelope["event"]["at"].endswith("Z")


def test_oversized_events_are_dropped_rather_than_sent():
    """Postgres rejects a NOTIFY payload over 8000 bytes — better to lose a UI tick."""
    encoder = EventEncoder("node-1")
    assert encoder.job_progress(uuid.uuid4(), 0.5, message="x" * 9000) is None
