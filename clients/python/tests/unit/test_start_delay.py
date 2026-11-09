"""Invalid start-delay configuration fails before touching storage."""

from __future__ import annotations

from dataclasses import dataclass
from unittest.mock import MagicMock

import pytest

from taskscheduler import ConfigurationError, Scheduler, SchedulerConfig, Storage, job_type


@job_type
@dataclass
class Work:
    pass


@pytest.mark.parametrize("delay", [-1, float("inf"), float("nan"), 0.0001, 1e30])
async def test_invalid_start_delay_is_rejected_before_writing(delay):
    storage = MagicMock(spec=Storage)
    scheduler = Scheduler(SchedulerConfig(dsn="postgresql://unused"), storage=storage)

    with pytest.raises(ConfigurationError, match="max_start_delay_seconds"):
        await scheduler.enqueue(Work(), max_start_delay_seconds=delay)
    with pytest.raises(ConfigurationError, match="max_start_delay_seconds"):
        await scheduler.recurring("work", "* * * * *", Work(), max_start_delay_seconds=delay)

    storage.transaction.assert_not_called()
