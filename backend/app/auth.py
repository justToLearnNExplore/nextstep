"""Identity without passwords.

Seniors: an anonymous Firebase account created on the phone during onboarding (they only say
their name). The app sends its Firebase ID token; we verify it with Google's public keys.
In local development (NEXTSTEP_REQUIRE_AUTH=false) an X-Device-Id header stands in for it.

Family viewers: a random token obtained by redeeming a single-use invite code (X-Viewer-Token).
"""

import re

from fastapi import Header, HTTPException
from google.auth.transport import requests as google_requests
from google.oauth2 import id_token

from .config import settings

_request = google_requests.Request()
_DEVICE_ID = re.compile(r"^[A-Za-z0-9_-]{8,64}$")


def current_user(
    authorization: str | None = Header(default=None),
    x_device_id: str | None = Header(default=None),
) -> str:
    if authorization and authorization.startswith("Bearer "):
        try:
            claims = id_token.verify_firebase_token(
                authorization.removeprefix("Bearer "), _request, audience=settings.project
            )
        except ValueError as e:
            raise HTTPException(401, "Invalid Firebase ID token") from e
        if not claims:
            raise HTTPException(401, "Invalid Firebase ID token")
        return str(claims["user_id"])
    if settings.require_auth:
        raise HTTPException(401, "Missing Firebase ID token")
    if x_device_id and _DEVICE_ID.match(x_device_id):
        return f"dev-{x_device_id}"
    return "anon"
