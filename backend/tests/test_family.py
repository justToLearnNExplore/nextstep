import pytest
from fastapi.testclient import TestClient

import app.main as main
from app.protocol import Gate, GatedAction, ScamCheckResponse, StepResponse, TaskPlan
from app.store import MemoryStore

SCREEN = {"package": "com.grofers.customerapp", "width": 1000, "height": 2000, "nodes": []}
SENIOR = {"X-Device-Id": "kamala-phone-01"}


@pytest.fixture(autouse=True)
def fresh_store(monkeypatch):
    monkeypatch.setattr(main, "store", MemoryStore())


@pytest.fixture
def client():
    return TestClient(main.app)


def link_family(client):
    client.put("/v1/me", json={"display_name": "Kamala", "language": "kn-IN"}, headers=SENIOR)
    inv = client.post("/v1/family/invite", headers=SENIOR).json()
    assert inv["join_url"].endswith(f"/join?code={inv['code']}")
    code = inv["code"][:4] + "-" + inv["code"][4:].lower()  # typed by hand: dash, lower case
    joined = client.post("/v1/family/join", json={"code": code, "viewer_name": "Ravi"}).json()
    assert joined["senior_name"] == "Kamala"
    return {"X-Viewer-Token": joined["viewer_token"]}


def test_invite_is_single_use(client):
    client.put("/v1/me", json={"display_name": "Kamala"}, headers=SENIOR)
    code = client.post("/v1/family/invite", headers=SENIOR).json()["code"]
    assert client.post("/v1/family/join", json={"code": code}).status_code == 200
    assert client.post("/v1/family/join", json={"code": code}).status_code == 404


def test_feed_requires_viewer_token(client):
    assert client.get("/v1/family/feed").status_code == 401
    assert client.get("/v1/family/feed", headers={"X-Viewer-Token": "nope"}).status_code == 401


def test_task_confirmations_and_private_steps_reach_family(client, monkeypatch):
    viewer = link_family(client)

    async def fake_plan(goal, lang, screen, installed=None):
        return TaskPlan(summary="Order milk?", steps=[], operator_goal=goal)

    turns = iter([
        StepResponse(actions=[
            GatedAction(call_id="c1", name="hand_over_to_user", intent="Login", gate=Gate.PRIVATE),
        ]),
        StepResponse(actions=[
            GatedAction(call_id="c2", name="click", intent="Place order: Amul milk 1 L, ₹68, cash on delivery", gate=Gate.CONFIRM),
        ]),
        StepResponse(done=True, message="Order placed"),
    ])

    async def fake_next(task, screen, results):
        res = next(turns)
        if res.done:
            task.status = "done"
        return res

    monkeypatch.setattr(main, "plan_task", fake_plan)
    monkeypatch.setattr(main, "next_actions", fake_next)

    tid = client.post("/v1/tasks", json={"goal": "Order milk on Blinkit", "screen": SCREEN}, headers=SENIOR).json()["task_id"]
    client.post(f"/v1/tasks/{tid}/consent", json={"approved": True}, headers=SENIOR)
    step = lambda results: client.post(f"/v1/tasks/{tid}/step", json={"screen": SCREEN, "results": results}, headers=SENIOR)  # noqa: E731
    step([])
    step([{"call_id": "c1", "name": "hand_over_to_user", "result": {"ok": True, "handed_to_user": True}}])
    step([{"call_id": "c2", "name": "click", "result": {"ok": True, "safety_acknowledgement": True}}])

    feed = client.get("/v1/family/feed", headers=viewer).json()
    assert feed["senior_name"] == "Kamala"
    kinds = [e["kind"] for e in feed["events"]]
    assert kinds[:4] == ["task_done", "confirmed", "private", "task_started"]
    confirmed = feed["events"][1]
    assert confirmed["severity"] == "confirmed" and "₹68" in confirmed["title"] and confirmed["detail"] == "Kamala said yes"


def test_scam_alert_without_message_text(client, monkeypatch):
    viewer = link_family(client)

    async def fake_check(req):
        return ScamCheckResponse(risk="high", reasons=["lookalike_domain:sbi-kyc.in", "urgency"], impersonates="State Bank of India",
                                 warning="...", official_url="https://www.onlinesbi.sbi", official_label="State Bank of India")

    monkeypatch.setattr(main, "check_message", fake_check)
    secret_text = "Dear customer update KYC at sbi-kyc.in OTP 123456"
    client.post("/v1/scam/check", json={"text": secret_text, "language": "kn-IN"}, headers=SENIOR)
    e = client.get("/v1/family/feed", headers=viewer).json()["events"][0]
    assert e["kind"] == "scam_blocked" and e["severity"] == "alert"
    assert "State Bank of India" in e["detail"] and "123456" not in e["detail"] and "sbi-kyc.in" not in e["detail"]


def test_revoke_cuts_off_viewers(client):
    viewer = link_family(client)
    assert client.get("/v1/me", headers=SENIOR).json()["viewers"] == ["Ravi"]
    assert client.post("/v1/family/revoke", headers=SENIOR).json() == {"revoked": 1}
    assert client.get("/v1/family/feed", headers=viewer).status_code == 401


def test_client_events_allow_list(client):
    viewer = link_family(client)
    assert client.post("/v1/events", json={"kind": "share_sent", "data": {"recipient": "Dr. Rao"}}, headers=SENIOR).status_code == 200
    assert client.post("/v1/events", json={"kind": "anything_else"}, headers=SENIOR).status_code == 400
    assert client.get("/v1/family/feed", headers=viewer).json()["events"][0]["title"] == "Sent medicine photo to Dr. Rao on WhatsApp"


def test_api_prefix_from_firebase_hosting(client):
    assert client.get("/api/health").json() == {"status": "ok"}
