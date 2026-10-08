"""Firebase Authentication: the app sends a Firebase ID token; we verify it with Google's keys."""

from fastapi import Header, HTTPException
from google.auth.transport import requests as google_requests
from google.oauth2 import id_token

from .config import settings

_request = google_requests.Request()


def current_user(authorization: str | None = Header(default=None)) -> str:
    if not authorization or not authorization.startswith("Bearer "):
        if settings.require_auth:
            raise HTTPException(401, "Missing Firebase ID token")
        return "anon"
    try:
        claims = id_token.verify_firebase_token(authorization.removeprefix("Bearer "), _request, audience=settings.project)
    except ValueError as e:
        raise HTTPException(401, "Invalid Firebase ID token") from e
    return claims["user_id"] if claims else "anon"
