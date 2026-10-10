"""Runs a single-shot ADK agent and returns its structured (pydantic) output, falling back to
lighter models when Gemini is overloaded."""

import base64
import uuid
from typing import TypeVar

from google.adk.agents import LlmAgent
from google.adk.runners import Runner
from google.adk.sessions import InMemorySessionService
from google.genai import types
from pydantic import BaseModel

from ..config import settings
from ..resilience import first_working

T = TypeVar("T", bound=BaseModel)

APP_NAME = "nextstep"
_sessions = InMemorySessionService()
_runners: dict[str, Runner] = {}
_variants: dict[tuple[str, str], LlmAgent] = {}


def agent_config(timeout_ms: int | None = None) -> types.GenerateContentConfig:
    """Shared generation config. (Sampling params like temperature are deprecated for Gemini 3.x.)"""
    return types.GenerateContentConfig(http_options=types.HttpOptions(timeout=timeout_ms or settings.agent_timeout_ms))


def _variant(agent: LlmAgent, model: str) -> LlmAgent:
    """The same agent on another model (cached), for fallbacks."""
    if model == agent.model:
        return agent
    key = (agent.name, model)
    if key not in _variants:
        suffix = "".join(c if c.isalnum() else "_" for c in model)
        _variants[key] = agent.clone(update={"model": model, "name": f"{agent.name}__{suffix}"})
    return _variants[key]


def _runner(agent: LlmAgent) -> Runner:
    if agent.name not in _runners:
        _runners[agent.name] = Runner(
            app_name=APP_NAME, agent=agent, session_service=_sessions, auto_create_session=True
        )
    return _runners[agent.name]


def image_part(b64: str | None) -> list[types.Part]:
    if not b64:
        return []
    return [types.Part.from_bytes(data=base64.b64decode(b64), mime_type="image/jpeg")]


async def _run_once(agent: LlmAgent, schema: type[T], parts: list[types.Part], user_id: str) -> T:
    """One request/response turn. Each call uses a fresh session: these agents are stateless."""
    session_id = uuid.uuid4().hex
    final_text = ""
    try:
        async for event in _runner(agent).run_async(
            user_id=user_id,
            session_id=session_id,
            new_message=types.Content(role="user", parts=parts),
        ):
            if event.is_final_response() and event.content and event.content.parts:
                final_text = "".join(p.text or "" for p in event.content.parts)
    finally:
        await _sessions.delete_session(app_name=APP_NAME, user_id=user_id, session_id=session_id)
    return schema.model_validate_json(final_text)


async def run_structured(
    agent: LlmAgent,
    schema: type[T],
    parts: list[types.Part],
    fallbacks: tuple[str, ...] = (),
    user_id: str = "anon",
) -> T:
    models = [str(agent.model), *[m for m in fallbacks if m != agent.model]]
    _, result = await first_working(
        models, lambda m: _run_once(_variant(agent, m), schema, parts, user_id), what=agent.name
    )
    return result
