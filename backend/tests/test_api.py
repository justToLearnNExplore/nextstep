from fastapi.testclient import TestClient

import app.main as main
from app.protocol import StepResponse, TaskPlan

SCREEN = {"package": "com.android.launcher", "width": 1000, "height": 2000, "nodes": []}


def test_task_flow_requires_consent(monkeypatch):
    async def fake_plan(goal, lang, screen, installed=None):
        return TaskPlan(summary="Order milk?", steps=["Open Blinkit"], operator_goal=goal)

    async def fake_next(task, screen, results):
        return StepResponse(done=True, message="ok")

    monkeypatch.setattr(main, "plan_task", fake_plan)
    monkeypatch.setattr(main, "next_actions", fake_next)
    c = TestClient(main.app)

    r = c.post("/v1/tasks", json={"goal": "order milk", "language": "hi-IN", "screen": SCREEN}).json()
    tid = r["task_id"]
    # No step before consent.
    assert c.post(f"/v1/tasks/{tid}/step", json={"screen": SCREEN}).status_code == 409
    assert c.post(f"/v1/tasks/{tid}/consent", json={"approved": True}).json()["status"] == "running"
    assert c.post(f"/v1/tasks/{tid}/step", json={"screen": SCREEN}).json()["done"] is True
    assert c.post(f"/v1/tasks/{tid}/stop").json()["status"] == "stopped"
    assert c.post(f"/v1/tasks/{tid}/step", json={"screen": SCREEN}).status_code == 409


def test_healthz():
    assert TestClient(main.app).get("/healthz").json() == {"status": "ok"}
