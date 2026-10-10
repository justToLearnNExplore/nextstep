import os
from dataclasses import dataclass


@dataclass(frozen=True)
class Settings:
    operator_model: str = os.getenv("NEXTSTEP_OPERATOR_MODEL", "gemini-3.8-flash")
    reasoning_model: str = os.getenv("NEXTSTEP_REASONING_MODEL", "gemini-3.8-flash")
    fast_model: str = os.getenv("NEXTSTEP_FAST_MODEL", "gemini-3.5-flash-lite")
    store: str = os.getenv("NEXTSTEP_STORE", "memory")
    project: str | None = os.getenv("GOOGLE_CLOUD_PROJECT") or None
    require_auth: bool = os.getenv("NEXTSTEP_REQUIRE_AUTH", "false").lower() == "true"
    max_turns: int = int(os.getenv("NEXTSTEP_MAX_TURNS", "40"))
    # Public URL of the family dashboard (Firebase Hosting); used to build invite links.
    dashboard_url: str = os.getenv("NEXTSTEP_DASHBOARD_URL", "http://localhost:5173").rstrip("/")


settings = Settings()
