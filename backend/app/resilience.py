"""Gemini can be briefly overloaded (503), slow (504/timeouts) or rate-limited (429), especially
on the free tier. These helpers retry with backoff and fall back to lighter models, so a senior's
task degrades to "a bit slower" instead of "something went wrong"."""

import asyncio
import logging
import random
import re
from collections.abc import Awaitable, Callable
from typing import TypeVar

T = TypeVar("T")
log = logging.getLogger("nextstep")

TRANSIENT_CODES = {408, 429, 500, 502, 503, 504}


class AIUnavailable(Exception):
    """Gemini can't serve this request right now. kind: "busy" (try again soon) or "quota"."""

    def __init__(self, kind: str, retry_after_s: int = 60):
        super().__init__(kind)
        self.kind = kind
        self.retry_after_s = retry_after_s


def quota_exhausted(e: BaseException) -> bool:
    """Daily/plan quota (429 with a long retry, or a PerDay quota id): retrying now is pointless."""
    code = getattr(e, "code", None) or getattr(e, "status_code", None)
    if code == 402:  # prepaid credits depleted
        return True
    if code != 429:
        return False
    text = str(e)
    if "PerDay" in text or "billing details" in text or "prepayment" in text:
        return True
    m = re.search(r"retryDelay'?\"?:\s*'?\"?(\d+)s", text)
    return bool(m and int(m.group(1)) > 120)
_TRANSIENT_NAMES = ("Timeout", "Connection", "RateLimit", "InternalServer", "ServiceUnavailable", "Overloaded")


def is_transient(e: BaseException) -> bool:
    if quota_exhausted(e):
        return False
    for attr in ("code", "status_code"):
        if getattr(e, attr, None) in TRANSIENT_CODES:
            return True
    if isinstance(e, (TimeoutError, asyncio.TimeoutError)):
        return True
    return any(n in type(e).__name__ for n in _TRANSIENT_NAMES)


async def retry(fn: Callable[[], Awaitable[T]], attempts: int = 2, base_delay: float = 1.0, what: str = "") -> T:
    """Retries transient failures with jittered exponential backoff; other errors propagate."""
    for i in range(attempts):
        try:
            return await fn()
        except Exception as e:
            if not is_transient(e) or i == attempts - 1:
                raise
            delay = base_delay * 2**i + random.uniform(0, 0.5)
            log.warning("%s transient failure (%s); retrying in %.1fs", what, type(e).__name__, delay)
            await asyncio.sleep(delay)
    raise AssertionError("unreachable")


async def first_working(models: list[str], call: Callable[[str], Awaitable[T]], what: str = "") -> tuple[str, T]:
    """Tries each model in order (with retries); returns the model that answered and its result."""
    quota_hit = False
    for model in models:
        try:
            return model, await retry(lambda: call(model), what=f"{what}[{model}]")
        except Exception as e:
            if quota_exhausted(e):
                # Quotas are per model: another model may still have some left.
                quota_hit = True
                log.warning("%s: %s quota exhausted, trying next model", what, model)
                continue
            if not is_transient(e):
                raise
            log.warning("%s: %s unavailable (%s), trying next model", what, model, type(e).__name__)
    raise AIUnavailable("quota" if quota_hit else "busy", retry_after_s=3600 if quota_hit else 60)
