"""Learned skills: first run uses the AI, repeat runs replay the recipe with zero AI calls."""

import pytest
from fastapi.testclient import TestClient

import app.main as main
from app.protocol import Gate, GatedAction, StepResponse, TaskPlan
from app.rules import interruption_action
from app.skills import context_of, find_by_label
from app.store import MemoryStore
from app.protocol import Screen, UiNode

SENIOR = {"X-Device-Id": "kamala-phone-01"}
PKG = "com.grofers.customerapp"


def node(i, label, box, **kw):
    return {"i": i, "text": label, "b": list(box), **kw}


def screens(item: str, other: str):
    """Blinkit-like screens: home → search box focused → results with two ADD buttons → cart."""
    home = {"package": PKG, "width": 1000, "height": 2000, "nodes": [
        node(0, "Search for atta, dal and more", (50, 100, 950, 200), click=True),
        node(1, "Delivery in 10 minutes", (50, 220, 950, 300)),
    ]}
    search = {"package": PKG, "width": 1000, "height": 2000, "nodes": [
        node(0, "", (50, 100, 950, 200), edit=True, focus=True),
        node(1, "Recent searches", (50, 220, 950, 300)),
    ]}
    results = {"package": PKG, "width": 1000, "height": 2000, "nodes": [
        node(0, f"Amul {other}", (50, 300, 600, 380)), node(1, "ADD", (700, 300, 950, 380), click=True),
        node(2, f"Amul Taaza {item} 1 L", (50, 600, 600, 680)), node(3, "ADD", (700, 600, 950, 680), click=True),
    ]}
    cart = {"package": PKG, "width": 1000, "height": 2000, "nodes": [
        node(0, "Cash on Delivery", (50, 1500, 950, 1580), click=True),
        node(1, "Place Order ₹68", (50, 1800, 950, 1950), click=True),
    ]}
    return [home, search, results, cart]


class FakeGemini:
    """Plays the AI's part for the first run: picks the right element on each screen."""

    def __init__(self):
        self.calls = 0

    async def __call__(self, task, screen, hint=None):
        self.calls += 1
        task.ai_calls += 1
        if task.history and "Place order" in task.history[-1]:
            task.status = "done"
            return StepResponse(done=True, message="Order placed", source="ai")
        labels = {n.label(): n for n in screen.nodes}
        if any(n.edit and n.focus for n in screen.nodes):
            act = GatedAction(call_id=f"ai{self.calls}", name="type", args={"text": task.plan.params["item"], "press_enter": True},
                              intent="Type item", gate=Gate.AUTO)
        elif "Search for atta, dal and more" in labels:
            x, y = screen.norm_box(labels["Search for atta, dal and more"])
            act = GatedAction(call_id=f"ai{self.calls}", name="click", args={"x": x, "y": y}, intent="Tap search", gate=Gate.AUTO)
        elif "Place Order ₹68" in labels:
            x, y = screen.norm_box(labels["Place Order ₹68"])
            act = GatedAction(call_id=f"ai{self.calls}", name="click", args={"x": x, "y": y}, intent="Place order ₹68", gate=Gate.CONFIRM)
        else:  # results: the ADD next to the wanted item
            want = next(n for n in screen.nodes if task.plan.params["item"].lower() in n.label().lower())
            add = min((n for n in screen.nodes if n.label() == "ADD"), key=lambda n: abs(n.b[1] - want.b[1]))
            x, y = screen.norm_box(add)
            act = GatedAction(call_id=f"ai{self.calls}", name="click", args={"x": x, "y": y}, intent="Add to cart", gate=Gate.AUTO)
        return StepResponse(actions=[act], source="ai")


@pytest.fixture(autouse=True)
def fresh(monkeypatch):
    monkeypatch.setattr(main, "store", MemoryStore())


def run_task(client, monkeypatch, item, other, ai):
    async def plan(goal, lang, screen, installed=None):
        return TaskPlan(summary=f"Order {item}?", steps=[], operator_goal=goal, skill_key="blinkit_order_item", params={"item": item})

    monkeypatch.setattr(main, "plan_task", plan)
    monkeypatch.setattr(main.agent_loop, "next_actions", ai)
    start = client.post("/v1/tasks", json={"goal": f"order {item}", "screen": screens(item, other)[0]}, headers=SENIOR).json()
    tid = start["task_id"]
    client.post(f"/v1/tasks/{tid}/consent", json={"approved": True}, headers=SENIOR)
    gates, results = [], []
    for scr in screens(item, other) + [screens(item, other)[3]]:
        res = client.post(f"/v1/tasks/{tid}/step", json={"screen": scr, "results": results}, headers=SENIOR).json()
        if res["done"]:
            break
        a = res["actions"][0]
        gates.append((res["source"], a["name"], a["gate"], a["args"]))
        ok = {"ok": True, "safety_acknowledgement": True} if a["gate"] == "confirm" else {"ok": True}
        results = [{"call_id": a["call_id"], "name": a["name"], "result": ok}]
    # The final step reports the confirmed order; the AI (or recipe end) completes the task.
    task = main.store.get(tid)
    return start, gates, task


def test_first_run_learns_then_repeat_runs_with_zero_ai_calls(monkeypatch):
    client = TestClient(main.app)
    ai = FakeGemini()

    # First time: the AI works it out; on success the trace becomes a skill.
    start, gates, task = run_task(client, monkeypatch, "milk", "Butter", ai)
    assert not start["uses_saved_routine"]
    assert ai.calls == 5  # 4 actions + "task complete"
    assert task.status == "done"
    skill = main.store.get_skill("dev-kamala-phone-01__blinkit_order_item")
    assert [s.name for s in skill.steps] == ["click", "type", "click", "click"]
    assert skill.steps[1].text == "{item}"  # parameterised: works for any item
    assert skill.steps[2].label == "ADD" and "{item}" in skill.steps[2].context

    # Repeat with a different item: replayed from the recipe, no AI at all.
    ai2 = FakeGemini()
    start, gates, task = run_task(client, monkeypatch, "bread", "Milk", ai2)
    assert start["uses_saved_routine"]
    assert ai2.calls == 0 and task.ai_calls == 0 and task.skill_steps == 4
    sources = [g[0] for g in gates]
    assert sources == ["skill", "skill", "skill", "skill"]
    assert gates[1][3]["text"] == "bread"
    # Picked the ADD next to "Amul Taaza bread 1 L", not the first ADD on the list.
    assert gates[2][3]["y"] == 320
    # Safety unchanged: the final Place Order still needs a yes.
    assert gates[3][2] == "confirm"


def test_context_disambiguates_repeated_buttons():
    s = Screen.model_validate(screens("milk", "Butter")[2])
    adds = [n for n in s.nodes if n.label() == "ADD"]
    assert context_of(s, adds[1]) == "Amul Taaza milk 1 L"
    assert find_by_label(s, "ADD", "Amul Taaza milk 1 L") is adds[1]
    assert find_by_label(s, "ADD", "Something not on screen") is None


def test_interruptions_are_dismissed_for_free():
    s = Screen(package=PKG, width=1000, height=2000, nodes=[
        UiNode(i=0, text="Enjoying Blinkit? Rate us", b=[50, 800, 950, 900]),
        UiNode(i=1, text="Rate now", b=[50, 1000, 450, 1080], click=True),
        UiNode(i=2, text="Not now", b=[550, 1000, 950, 1080], click=True),
    ])
    act = interruption_action(s)
    assert act and "Not now" in act["intent"]
    assert interruption_action(Screen.model_validate(screens("milk", "x")[3])) is None
