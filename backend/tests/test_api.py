from fastapi.testclient import TestClient

import app.main as main
from app.protocol import StepResponse, TaskPlan

SCREEN = {"package": "com.android.launcher", "width": 1000, "height": 2000, "nodes": []}


def test_task_flow_requires_consent(monkeypatch):
    async def fake_plan(goal, lang, screen, installed=None):
        return TaskPlan(summary="Order milk?", steps=["Open Blinkit"], operator_goal=goal)

    async def fake_next(task, screen, hint=None):
        return StepResponse(done=True, message="ok")

    monkeypatch.setattr(main, "plan_task", fake_plan)
    monkeypatch.setattr(main.agent_loop, "next_actions", fake_next)
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


def test_ai_unavailable_becomes_503_with_code(monkeypatch):
    from app.resilience import AIUnavailable

    async def no_quota(goal, lang, screen, installed=None):
        raise AIUnavailable("quota", 3600)

    monkeypatch.setattr(main, "plan_task", no_quota)
    r = TestClient(main.app).post("/v1/tasks", json={"goal": "order milk", "screen": SCREEN})
    assert r.status_code == 503 and r.json()["detail"] == {"code": "ai_quota", "retry_after": 3600}


def test_slow_model_becomes_busy_not_a_hang(monkeypatch):
    import asyncio

    async def slow(goal, lang, screen, installed=None):
        await asyncio.sleep(5)

    import dataclasses

    monkeypatch.setattr(main, "settings", dataclasses.replace(main.settings, agent_budget_s=0.05))
    monkeypatch.setattr(main, "plan_task", slow)
    r = TestClient(main.app).post("/v1/tasks", json={"goal": "order milk", "screen": SCREEN})
    assert r.status_code == 503 and r.json()["detail"]["code"] == "ai_busy"


def test_stt_endpoint(monkeypatch):
    async def fake(audio_b64, lang, rate):
        assert lang == "kn-IN" and rate == 16000
        return "ಹಾಲು ಆರ್ಡರ್ ಮಾಡು"

    monkeypatch.setattr(main, "transcribe", fake)
    r = TestClient(main.app).post("/v1/stt", json={"language": "kn-IN", "audio_b64": "AAAA"})
    assert r.json() == {"text": "ಹಾಲು ಆರ್ಡರ್ ಮಾಡು"}
