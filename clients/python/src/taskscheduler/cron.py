"""Cron parsing for recurring definitions.

Only used to compute the *first* ``next_trigger_at`` when a definition is registered. Every
subsequent occurrence is computed by the Kotlin infra leader when it fires the job, so this
never drives execution — but it must agree with the Kotlin dialect or the first run lands at
the wrong time.

Kotlin (``core/backend/.../cron/CronExpr.kt``) picks its parser by field count:

* **5 fields** — classic UNIX ``m h dom month dow``.
* **6 fields** — the same with a *leading* seconds field (Spring 5.3 dialect, not Quartz:
  day-of-week keeps UNIX numbering and there is no ``?`` placeholder).

``croniter`` defaults to seconds *last* for 6-field expressions, so ``second_at_beginning``
is required here.
"""

from __future__ import annotations

from datetime import datetime, timezone
from zoneinfo import ZoneInfo, ZoneInfoNotFoundError

from croniter import CroniterBadCronError, croniter

from .errors import ConfigurationError

__all__ = ["next_trigger_at", "validate_cron"]


def _resolve_zone(timezone_name: str | None) -> timezone | ZoneInfo:
    if not timezone_name:
        return timezone.utc
    try:
        return ZoneInfo(timezone_name)
    except (ZoneInfoNotFoundError, ValueError) as exc:
        raise ConfigurationError(
            f"unknown IANA timezone {timezone_name!r} — use names like 'Europe/Berlin'"
        ) from exc


def _build(cron: str, base: datetime) -> croniter:
    fields = cron.split()
    if len(fields) not in (5, 6):
        raise ConfigurationError(
            f"cron {cron!r} has {len(fields)} fields — expected 5 (m h dom mon dow) "
            f"or 6 (with leading seconds)"
        )
    try:
        return croniter(cron, base, second_at_beginning=True)
    except (CroniterBadCronError, ValueError) as exc:
        raise ConfigurationError(f"invalid cron expression {cron!r}: {exc}") from exc


def validate_cron(cron: str, timezone_name: str | None = None) -> None:
    """Raise :class:`ConfigurationError` if the expression will not parse."""
    next_trigger_at(cron, timezone_name=timezone_name)


def next_trigger_at(
    cron: str, after: datetime | None = None, timezone_name: str | None = None
) -> datetime:
    """First occurrence strictly after ``after``, returned as an aware UTC datetime.

    ``timezone_name`` is an IANA name; ``None`` means UTC, matching a NULL
    ``recurring_job.timezone``. Evaluating in the target zone is what makes "every day at
    09:00 Europe/Berlin" hold across a DST change.
    """
    zone = _resolve_zone(timezone_name)
    base = after or datetime.now(timezone.utc)
    if base.tzinfo is None:
        base = base.replace(tzinfo=timezone.utc)
    local_base = base.astimezone(zone)
    nxt: datetime = _build(cron, local_base).get_next(datetime)
    if nxt.tzinfo is None:
        nxt = nxt.replace(tzinfo=zone)
    return nxt.astimezone(timezone.utc)
