"""Task state. Firestore in production (so Cloud Run instances stay stateless and family members
can later see the action log); in-memory for local development and tests."""

import time
import uuid
from typing import Any, Literal, Protocol

from pydantic import BaseModel, Field

from .config import settings
from .protocol import TaskPlan

Status = Literal["planned", "running", "declined", "stopped", "done", "failed"]


class TaskRecord(BaseModel):
    id: str = Field(default_factory=lambda: uuid.uuid4().hex)
    user_id: str = "anon"
    goal: str
    language: str
    plan: TaskPlan
    consented: bool = False
    status: Status = "planned"
    interaction_id: str | None = None
    turns: int = 0
    created_at: float = Field(default_factory=time.time)
    log: list[dict[str, Any]] = Field(default_factory=list)

    def add_log(self, kind: str, **data: Any) -> None:
        self.log.append({"at": time.time(), "kind": kind, **data})


class TaskStore(Protocol):
    def get(self, task_id: str) -> TaskRecord | None: ...
    def put(self, task: TaskRecord) -> None: ...


class MemoryStore:
    def __init__(self) -> None:
        self._tasks: dict[str, TaskRecord] = {}

    def get(self, task_id: str) -> TaskRecord | None:
        return self._tasks.get(task_id)

    def put(self, task: TaskRecord) -> None:
        self._tasks[task.id] = task


class FirestoreStore:
    def __init__(self) -> None:
        from google.cloud import firestore

        self._col = firestore.Client(project=settings.project).collection("tasks")

    def get(self, task_id: str) -> TaskRecord | None:
        snap = self._col.document(task_id).get()
        return TaskRecord.model_validate(snap.to_dict()) if snap.exists else None

    def put(self, task: TaskRecord) -> None:
        # Screenshots are never stored: the log only keeps intents, gates and decisions.
        self._col.document(task.id).set(task.model_dump(mode="json"))


def make_store() -> TaskStore:
    return FirestoreStore() if settings.store == "firestore" else MemoryStore()
