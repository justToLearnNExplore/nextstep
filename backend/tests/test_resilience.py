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
