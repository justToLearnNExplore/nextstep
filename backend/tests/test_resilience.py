import asyncio

import pytest

from app.resilience import first_working, is_transient, retry


class Busy(Exception):
    code = 503


class BadRequest(Exception):
    code = 400


_real_sleep = asyncio.sleep


def run(coro):
    return asyncio.run(coro)


def no_wait(_seconds):
    return _real_sleep(0)


def test_transient_classification():
    assert is_transient(Busy())
    assert is_transient(TimeoutError())
    assert not is_transient(BadRequest())
    assert not is_transient(ValueError("schema mismatch"))


def test_retry_recovers_from_a_busy_moment(monkeypatch):
    monkeypatch.setattr("app.resilience.asyncio.sleep", no_wait)
    calls = {"n": 0}

    async def flaky():
        calls["n"] += 1
        if calls["n"] == 1:
            raise Busy()
        return "ok"

    assert run(retry(flaky, attempts=3)) == "ok" and calls["n"] == 2


def test_falls_back_to_next_model(monkeypatch):
    monkeypatch.setattr("app.resilience.asyncio.sleep", no_wait)

    async def call(model):
        if model == "gemini-3.8-flash":
            raise Busy()
        return f"answered by {model}"

    assert run(first_working(["gemini-3.8-flash", "gemini-3.6-flash"], call)) == ("gemini-3.6-flash", "answered by gemini-3.6-flash")


def test_real_errors_are_not_hidden():
    async def call(model):
        raise BadRequest()

    with pytest.raises(BadRequest):
        run(first_working(["a", "b"], call))


class QuotaDaily(Exception):
    code = 429

    def __str__(self):
        return "429 RESOURCE_EXHAUSTED quotaId: GenerateRequestsPerDayPerProjectPerModel-FreeTier retryDelay': '15685s'"


class Prepay(Exception):
    code = 402


def test_daily_quota_is_not_retried_but_other_models_are_tried(monkeypatch):
    from app.resilience import AIUnavailable, quota_exhausted

    assert quota_exhausted(QuotaDaily()) and quota_exhausted(Prepay())
    assert not quota_exhausted(Busy())
    calls = []

    async def call(model):
        calls.append(model)
        raise QuotaDaily()

    with pytest.raises(AIUnavailable) as e:
        run(first_working(["a", "b"], call))
    assert e.value.kind == "quota" and calls == ["a", "b"]  # once each, no retries
