import os
from dataclasses import dataclass


def _list(name: str, default: str) -> tuple[str, ...]:
    return tuple(x.strip() for x in os.getenv(name, default).split(",") if x.strip())


@dataclass(frozen=True)
class Settings:
    # Primary models, then fallbacks tried in order when Gemini is overloaded or slow.
    # Free-tier deployments override these (see infra/deploy-backend.sh).
    operator_model: str = os.getenv("NEXTSTEP_OPERATOR_MODEL", "gemini-3.8-flash")
    operator_fallbacks: tuple[str, ...] = _list("NEXTSTEP_OPERATOR_FALLBACKS", "gemini-3.5-flash")
    reasoning_model: str = os.getenv("NEXTSTEP_REASONING_MODEL", "gemini-3.8-flash")
    reasoning_fallbacks: tuple[str, ...] = _list("NEXTSTEP_REASONING_FALLBACKS", "gemini-3.6-flash,gemini-3.5-flash-lite")
    fast_model: str = os.getenv("NEXTSTEP_FAST_MODEL", "gemini-3.5-flash-lite")
    fast_fallbacks: tuple[str, ...] = _list("NEXTSTEP_FAST_FALLBACKS", "gemini-3.6-flash")
    # Per-call timeouts (ms): fail fast and fall back instead of leaving a senior waiting.
    operator_timeout_ms: int = int(os.getenv("NEXTSTEP_OPERATOR_TIMEOUT_MS", "45000"))
    agent_timeout_ms: int = int(os.getenv("NEXTSTEP_AGENT_TIMEOUT_MS", "30000"))
    # Whole-request budgets (s). Must stay below the phone's read timeout (90 s) and Cloud Run's 120 s.
    step_budget_s: float = float(os.getenv("NEXTSTEP_STEP_BUDGET_S", "75"))
    agent_budget_s: float = float(os.getenv("NEXTSTEP_AGENT_BUDGET_S", "50"))
    store: str = os.getenv("NEXTSTEP_STORE", "memory")
    project: str | None = os.getenv("GOOGLE_CLOUD_PROJECT") or None
    require_auth: bool = os.getenv("NEXTSTEP_REQUIRE_AUTH", "false").lower() == "true"
    max_turns: int = int(os.getenv("NEXTSTEP_MAX_TURNS", "40"))
    # Public URL of the family dashboard (Firebase Hosting); used to build invite links.
    dashboard_url: str = os.getenv("NEXTSTEP_DASHBOARD_URL", "http://localhost:5173").rstrip("/")


settings = Settings()
