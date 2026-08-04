"""Cron dialect agreement with the Kotlin side, and retry backoff maths."""

from __future__ import annotations

from datetime import datetime, timezone

import pytest

from taskscheduler import (
    ConfigurationError,
    ExponentialBackoff,
    FixedDelay,
    NoRetry,
    equal_jitter,
    next_trigger_at,
    validate_cron,
)
from taskscheduler.retry import FULL_JITTER

UTC = timezone.utc


# --- cron ------------------------------------------------------------------------------


def test_five_field_expression_is_classic_unix():
    base = datetime(2026, 8, 3, 8, 0, tzinfo=UTC)
    assert next_trigger_at("0 9 * * *", after=base) == datetime(2026, 8, 3, 9, 0, tzinfo=UTC)


def test_six_field_expression_puts_seconds_first():
    """Kotlin parses 6-field cron as 'UNIX plus a leading seconds field' (Spring 5.3).

    croniter defaults to seconds *last*, so getting this wrong shifts every fire time.
    """
    base = datetime(2026, 8, 3, 8, 0, 0, tzinfo=UTC)
    assert next_trigger_at("30 0 9 * * *", after=base) == datetime(
        2026, 8, 3, 9, 0, 30, tzinfo=UTC
    )


def test_result_is_always_utc_aware():
    result = next_trigger_at("0 9 * * *", after=datetime(2026, 8, 3, 8, 0, tzinfo=UTC))
    assert result.tzinfo is not None
    assert result.utcoffset().total_seconds() == 0


def test_timezone_is_evaluated_in_the_target_zone():
    """09:00 Berlin is 07:00 UTC in summer — the point of storing an IANA name."""
    base = datetime(2026, 8, 3, 5, 0, tzinfo=UTC)
    result = next_trigger_at("0 9 * * *", after=base, timezone_name="Europe/Berlin")
    assert result == datetime(2026, 8, 3, 7, 0, tzinfo=UTC)


def test_timezone_shift_differs_across_dst():
    """The same expression maps to a different UTC hour in winter — 09:00 local either way."""
    summer = next_trigger_at(
        "0 9 * * *", after=datetime(2026, 7, 1, 0, 0, tzinfo=UTC), timezone_name="Europe/Berlin"
    )
    winter = next_trigger_at(
        "0 9 * * *", after=datetime(2026, 1, 1, 0, 0, tzinfo=UTC), timezone_name="Europe/Berlin"
    )
    assert summer.hour == 7
    assert winter.hour == 8


def test_naive_base_is_treated_as_utc():
    assert next_trigger_at("0 9 * * *", after=datetime(2026, 8, 3, 8, 0)) == datetime(
        2026, 8, 3, 9, 0, tzinfo=UTC
    )


@pytest.mark.parametrize("expression", ["", "0 9 * *", "0 9 * * * * *", "not-a-cron"])
def test_bad_expressions_are_rejected(expression):
    with pytest.raises(ConfigurationError):
        validate_cron(expression)


def test_unknown_timezone_is_rejected():
    with pytest.raises(ConfigurationError, match="unknown IANA timezone"):
        next_trigger_at("0 9 * * *", timezone_name="Mars/Olympus")


# --- retry -----------------------------------------------------------------------------


def test_exponential_backoff_doubles_from_the_first_attempt():
    policy = ExponentialBackoff(max_attempts=5, initial_seconds=1.0, multiplier=2.0)
    assert [policy.next_backoff(a) for a in (1, 2, 3, 4)] == [1.0, 2.0, 4.0, 8.0]


def test_exponential_backoff_respects_its_cap():
    policy = ExponentialBackoff(max_attempts=20, initial_seconds=1.0, max_seconds=10.0)
    assert policy.next_backoff(10) == 10.0


def test_fixed_delay_is_constant():
    policy = FixedDelay(max_attempts=3, delay_seconds=7.5)
    assert policy.next_backoff(1) == policy.next_backoff(3) == 7.5


def test_no_retry_allows_a_single_attempt():
    assert NoRetry().max_attempts == 1


def test_full_jitter_stays_within_the_computed_delay():
    policy = ExponentialBackoff(initial_seconds=8.0, multiplier=1.0, jitter=FULL_JITTER)
    assert all(0.0 <= policy.next_backoff(1) <= 8.0 for _ in range(50))


def test_equal_jitter_stays_within_its_band():
    policy = ExponentialBackoff(initial_seconds=10.0, multiplier=1.0, jitter=equal_jitter(0.25))
    assert all(7.5 <= policy.next_backoff(1) <= 12.5 for _ in range(50))


def test_equal_jitter_factor_is_validated():
    with pytest.raises(ValueError):
        equal_jitter(1.5)
