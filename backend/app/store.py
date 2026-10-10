"""Persistence. Firestore in production (Cloud Run instances stay stateless); in-memory for local
development and tests. Screenshots, photos, message texts and secrets are never stored."""

import hashlib
import secrets
import time
import uuid
from typing import Any, Literal, Protocol

from pydantic import BaseModel, Field

from .config import settings
from .protocol import TaskPlan

Status = Literal["planned", "running", "declined", "stopped", "done", "failed"]
Severity = Literal["routine", "confirmed", "declined", "private", "alert", "done", "failed"]


class TaskRecord(BaseModel):
    id: str = Field(default_factory=lambda: uuid.uuid4().hex)
    user_id: str = "anon"
    goal: str
    language: str
    plan: TaskPlan
    consented: bool = False
    status: Status = "planned"
    interaction_id: str | None = None
    operator_model: str | None = None  # chosen on the first step; an interaction can't switch models
    turns: int = 0
    created_at: float = Field(default_factory=time.time)
    log: list[dict[str, Any]] = Field(default_factory=list)
    # call_id → {name, intent, gate} of actions sent to the phone, to interpret their results.
    pending: dict[str, dict[str, str]] = Field(default_factory=dict)

    def add_log(self, kind: str, **data: Any) -> None:
        self.log.append({"at": time.time(), "kind": kind, **data})


class Profile(BaseModel):
    uid: str
    display_name: str = ""
    language: str = "en-IN"
    created_at: float = Field(default_factory=time.time)
    last_seen: float = Field(default_factory=time.time)


class FamilyEvent(BaseModel):
    """One line on the family timeline. Plain language; never secrets or message contents."""

    id: str = Field(default_factory=lambda: uuid.uuid4().hex)
    at: float = Field(default_factory=time.time)
    kind: str
    severity: Severity = "routine"
    title: str
    detail: str = ""
    task_id: str | None = None


class Invite(BaseModel):
    code: str
    uid: str
    expires_at: float
    used: bool = False


class Viewer(BaseModel):
    token_hash: str
    uid: str
    name: str = ""
    created_at: float = Field(default_factory=time.time)
    revoked: bool = False


def new_invite_code() -> str:
    """8 characters from an unambiguous alphabet (no 0/O, 1/I/L), shown as XXXX-XXXX."""
    alphabet = "ABCDEFGHJKMNPQRSTUVWXYZ23456789"
    return "".join(secrets.choice(alphabet) for _ in range(8))


def hash_token(token: str) -> str:
    return hashlib.sha256(token.encode()).hexdigest()


class Store(Protocol):
    def get(self, task_id: str) -> TaskRecord | None: ...
    def put(self, task: TaskRecord) -> None: ...
    def get_profile(self, uid: str) -> Profile | None: ...
    def put_profile(self, p: Profile) -> None: ...
    def add_event(self, uid: str, e: FamilyEvent) -> None: ...
    def list_events(self, uid: str, limit: int) -> list[FamilyEvent]: ...
    def put_invite(self, inv: Invite) -> None: ...
    def get_invite(self, code: str) -> Invite | None: ...
    def put_viewer(self, v: Viewer) -> None: ...
    def get_viewer(self, token_hash: str) -> Viewer | None: ...
    def list_viewers(self, uid: str) -> list[Viewer]: ...


class MemoryStore:
    def __init__(self) -> None:
        self._tasks: dict[str, TaskRecord] = {}
        self._profiles: dict[str, Profile] = {}
        self._events: dict[str, list[FamilyEvent]] = {}
        self._invites: dict[str, Invite] = {}
        self._viewers: dict[str, Viewer] = {}

    def get(self, task_id: str) -> TaskRecord | None:
        return self._tasks.get(task_id)

    def put(self, task: TaskRecord) -> None:
        self._tasks[task.id] = task

    def get_profile(self, uid: str) -> Profile | None:
        return self._profiles.get(uid)

    def put_profile(self, p: Profile) -> None:
        self._profiles[p.uid] = p

    def add_event(self, uid: str, e: FamilyEvent) -> None:
        self._events.setdefault(uid, []).append(e)

    def list_events(self, uid: str, limit: int) -> list[FamilyEvent]:
        return sorted(self._events.get(uid, []), key=lambda e: e.at, reverse=True)[:limit]

    def put_invite(self, inv: Invite) -> None:
        self._invites[inv.code] = inv

    def get_invite(self, code: str) -> Invite | None:
        return self._invites.get(code)

    def put_viewer(self, v: Viewer) -> None:
        self._viewers[v.token_hash] = v

    def get_viewer(self, token_hash: str) -> Viewer | None:
        return self._viewers.get(token_hash)

    def list_viewers(self, uid: str) -> list[Viewer]:
        return [v for v in self._viewers.values() if v.uid == uid]


class FirestoreStore:
    """users/{uid} profile · users/{uid}/events/{id} · tasks/{id} · invites/{code} · viewers/{hash}"""

    def __init__(self) -> None:
        from google.cloud import firestore

        self._fs = firestore
        self._db = firestore.Client(project=settings.project)

    def get(self, task_id: str) -> TaskRecord | None:
        snap = self._db.collection("tasks").document(task_id).get()
        return TaskRecord.model_validate(snap.to_dict()) if snap.exists else None

    def put(self, task: TaskRecord) -> None:
        self._db.collection("tasks").document(task.id).set(task.model_dump(mode="json"))

    def get_profile(self, uid: str) -> Profile | None:
        snap = self._db.collection("users").document(uid).get()
        return Profile.model_validate(snap.to_dict()) if snap.exists else None

    def put_profile(self, p: Profile) -> None:
        self._db.collection("users").document(p.uid).set(p.model_dump(mode="json"))

    def add_event(self, uid: str, e: FamilyEvent) -> None:
        self._db.collection("users").document(uid).collection("events").document(e.id).set(e.model_dump(mode="json"))

    def list_events(self, uid: str, limit: int) -> list[FamilyEvent]:
        q = (
            self._db.collection("users").document(uid).collection("events")
            .order_by("at", direction=self._fs.Query.DESCENDING).limit(limit)
        )
        return [FamilyEvent.model_validate(d.to_dict()) for d in q.stream()]

    def put_invite(self, inv: Invite) -> None:
        self._db.collection("invites").document(inv.code).set(inv.model_dump(mode="json"))

    def get_invite(self, code: str) -> Invite | None:
        snap = self._db.collection("invites").document(code).get()
        return Invite.model_validate(snap.to_dict()) if snap.exists else None

    def put_viewer(self, v: Viewer) -> None:
        self._db.collection("viewers").document(v.token_hash).set(v.model_dump(mode="json"))

    def get_viewer(self, token_hash: str) -> Viewer | None:
        snap = self._db.collection("viewers").document(token_hash).get()
        return Viewer.model_validate(snap.to_dict()) if snap.exists else None

    def list_viewers(self, uid: str) -> list[Viewer]:
        return [Viewer.model_validate(d.to_dict()) for d in self._db.collection("viewers").where("uid", "==", uid).stream()]


def make_store() -> Store:
    return FirestoreStore() if settings.store == "firestore" else MemoryStore()
