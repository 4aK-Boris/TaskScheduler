"""Continuous mode validation and producer contract."""

from dataclasses import dataclass
from datetime import datetime, timezone
from unittest.mock import MagicMock

import pytest

from taskscheduler import ConfigurationError, Scheduler, SchedulerConfig, Storage, job_type


@job_type
@dataclass
class Work:
    pass


@pytest.mark.parametrize("cron, tz", [("* * * * *", None), ("", "UTC")])
async def test_continuous_mode_rejects_cron_and_timezone_before_writing(cron, tz):
    storage = MagicMock(spec=Storage)
    scheduler = Scheduler(SchedulerConfig(dsn="postgresql://unused"), storage=storage)

    with pytest.raises(ConfigurationError, match="repeat_after_completion"):
        await scheduler.recurring(
            "work", cron, Work(), timezone_name=tz, repeat_after_completion=True
        )

    storage.transaction.assert_not_called()


async def test_continuous_mode_is_due_now_and_forces_skip_overlap():
    storage = MagicMock(spec=Storage)
    scheduler = Scheduler(SchedulerConfig(dsn="postgresql://unused"), storage=storage)
    before = datetime.now(timezone.utc)

    await scheduler.recurring("work", "", Work(), repeat_after_completion=True)

    kwargs = storage.upsert_recurring.await_args.kwargs
    assert kwargs["repeat_after_completion"] is True
    assert kwargs["overlap_policy"] == "SKIP"
    assert before <= kwargs["next_trigger_at"] <= datetime.now(timezone.utc)
