"""Gemini can be briefly overloaded (503), slow (504/timeouts) or rate-limited (429), especially
on the free tier. These helpers retry with backoff and fall back to lighter models, so a senior's
task degrades to "a bit slower" instead of "something went wrong"."""

import asyncio
import logging
import random
from collections.abc import Awaitable, Callable
from typing import TypeVar

T = TypeVar("T")
log = logging.getLogger("nextstep")

TRANSIENT_CODES = {408, 429, 500, 502, 503, 504}
_TRANSIENT_NAMES = ("Timeout", "Connection", "RateLimit", "InternalServer", "ServiceUnavailable", "Overloaded")


def is_transient(e: BaseException) -> bool:
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
    last: Exception | None = None
    for model in models:
        try:
            return model, await retry(lambda: call(model), what=f"{what}[{model}]")
        except Exception as e:
            if not is_transient(e):
                raise
            last = e
            log.warning("%s: %s unavailable (%s), trying next model", what, model, type(e).__name__)
    assert last is not None
    raise last
