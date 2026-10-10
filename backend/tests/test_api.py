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
    assert TestClient(main.app).get("/health").json() == {"status": "ok"}


def test_medicine_read_endpoint(monkeypatch):
    from app.protocol import MedicineInfo

    async def fake_read(req):
        assert req.recipient == "doctor" and req.ocr_text == "DOLO 650"
        return MedicineInfo(readable=True, name="Dolo 650", say_to_user="This looks like Dolo 650.", caption="Doctor, this is Dolo 650.")

    monkeypatch.setattr(main, "read_medicine", fake_read)
    r = TestClient(main.app).post("/v1/medicine/read", json={"image_b64": "AAAA", "ocr_text": "DOLO 650", "language": "en-IN"})
    assert r.status_code == 200 and r.json()["name"] == "Dolo 650"


def test_planner_schema_supports_medicine_flow():
    plan = TaskPlan(summary="s", steps=[], operator_goal="g", special_flow="medicine_photo", recipient="doctor")
    assert plan.model_dump()["special_flow"] == "medicine_photo"
