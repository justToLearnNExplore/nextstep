"""Runs a single-shot ADK agent and returns its structured (pydantic) output."""

import base64
import uuid
from typing import TypeVar

from google.adk.agents import LlmAgent
from google.adk.runners import Runner
from google.adk.sessions import InMemorySessionService
from google.genai import types
from pydantic import BaseModel

T = TypeVar("T", bound=BaseModel)

APP_NAME = "nextstep"
_sessions = InMemorySessionService()
_runners: dict[str, Runner] = {}


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


async def run_structured(agent: LlmAgent, schema: type[T], parts: list[types.Part], user_id: str = "anon") -> T:
    """One request/response turn. Each call uses a fresh session: these agents are stateless."""
    session_id = uuid.uuid4().hex
    final_text = ""
    async for event in _runner(agent).run_async(
        user_id=user_id,
        session_id=session_id,
        new_message=types.Content(role="user", parts=parts),
    ):
        if event.is_final_response() and event.content and event.content.parts:
            final_text = "".join(p.text or "" for p in event.content.parts)
    await _sessions.delete_session(app_name=APP_NAME, user_id=user_id, session_id=session_id)
    return schema.model_validate_json(final_text)
