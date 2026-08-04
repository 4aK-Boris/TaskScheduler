"""Retry policies — how long to wait before the next attempt.

Mirrors ``core/backend/.../handler/retry/RetryPolicies.kt``. The delay is handed to
RabbitMQ as the ``x-delay`` header on the retry message, so the wait costs nothing on the
worker side: the broker holds the message and redelivers when it expires.

The override chain is the same as Kotlin's: per-enqueue policy > per-handler policy >
the scheduler-wide default.
"""

from __future__ import annotations

import random
from abc import ABC, abstractmethod
from dataclasses import dataclass

__all__ = [
    "RetryPolicy",
    "NoRetry",
    "FixedDelay",
    "ExponentialBackoff",
    "Jitter",
    "NO_JITTER",
    "FULL_JITTER",
    "equal_jitter",
]


@dataclass(frozen=True, slots=True)
class Jitter:
    """Randomisation applied to a computed backoff, to break up retry stampedes.

    ``kind='none'`` returns the delay unchanged; ``'full'`` picks uniformly from
    ``[0, delay]``; ``'equal'`` stays within ``±factor`` of the delay, which keeps a
    predictable floor while still spreading load.
    """

    kind: str = "none"
    factor: float = 0.0

    def apply(self, seconds: float) -> float:
        if self.kind == "full":
            return random.uniform(0.0, seconds)
        if self.kind == "equal":
            low = seconds * (1.0 - self.factor)
            high = seconds * (1.0 + self.factor)
            return random.uniform(low, high)
        return seconds


NO_JITTER = Jitter("none", 0.0)
FULL_JITTER = Jitter("full", 0.0)


def equal_jitter(factor: float = 0.25) -> Jitter:
    """±``factor`` around the computed delay (``0.25`` = ±25%)."""
    if not 0.0 <= factor <= 1.0:
        raise ValueError("jitter factor must be within 0.0..1.0")
    return Jitter("equal", factor)


class RetryPolicy(ABC):
    """Decides whether there is another attempt, and how long to wait for it."""

    max_attempts: int

    @abstractmethod
    def next_backoff(self, attempts: int) -> float:
        """Seconds to wait before attempt ``attempts + 1``. ``attempts`` is 1-based."""


@dataclass(frozen=True, slots=True)
class NoRetry(RetryPolicy):
    """One shot. A failure goes straight to terminal FAILED."""

    max_attempts: int = 1

    def next_backoff(self, attempts: int) -> float:
        return 0.0


@dataclass(frozen=True, slots=True)
class FixedDelay(RetryPolicy):
    """Constant wait between attempts."""

    max_attempts: int = 3
    delay_seconds: float = 5.0
    jitter: Jitter = NO_JITTER

    def next_backoff(self, attempts: int) -> float:
        return max(0.0, self.jitter.apply(self.delay_seconds))


@dataclass(frozen=True, slots=True)
class ExponentialBackoff(RetryPolicy):
    """``initial * multiplier ** (attempts - 1)``, capped at ``max_seconds``."""

    max_attempts: int = 3
    initial_seconds: float = 1.0
    max_seconds: float = 3600.0
    multiplier: float = 2.0
    jitter: Jitter = NO_JITTER

    def next_backoff(self, attempts: int) -> float:
        exponent = max(0, attempts - 1)
        raw = self.initial_seconds * (self.multiplier**exponent)
        capped = min(raw, self.max_seconds)
        return max(0.0, self.jitter.apply(capped))
