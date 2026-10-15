"""Decides each step of a task, cheapest source first:

  1. rule   – a known interruption (rate-us, update, ad) is dismissed for free
  2. link   – an allowlisted deep link jumps straight to search results (first step only)
  3. skill  – the next step of a learned recipe, matched by label on the live screen (0 AI calls)
  4. ai     – Gemini Computer Use, stateless and lean (with the recipe's next step as a hint)

Whatever the source, every action passes the Guardian (and later the phone's SafetyGate).
When a task succeeds, its trace becomes / reinforces a skill for next time.
"""

import logging
import re
from typing import Any

from . import family
from .guardian import ALLOWED_LINK_PREFIXES, classify
from .i18n import phrase
from .operator import next_actions
from .protocol import GatedAction, Screen, StepResponse
from .rules import interruption_action, needs_screenshot
from .skills import (
    MIN_MATCH, Skill, SkillStep, fill, generic_steps, learn, multi_item, replay_action, similarity, step_from_action, target_node,
)
from .store import Store, TaskRecord

log = logging.getLogger("nextstep")
LOOKAHEAD = 3  # how far ahead in a recipe we look if the app skipped a screen

BLINKIT = "com.grofers.customerapp"
CART_BAR = re.compile(r"view cart|\b\d+\s+items?\b", re.I)
CART_PAGE = re.compile(r"bill details|grand total|to pay\b|delivery instructions", re.I)
CART_EMPTY = re.compile(r"cart is empty|nothing in (your|the) cart", re.I)
CLEAR_CART_HINT = (
    "before ordering, empty the cart left over from earlier: open it (View cart) and tap '−' on every "
    "item until the cart is empty. Do not order anything yet; then go back to the search"
)


def track_cart(task: TaskRecord, screen: Screen) -> None:
    """A new Blinkit order starts from an empty cart, so last time's items are never ordered again.
    Watches the screen: a cart bar before we've added anything means old items to remove first."""
    ordering = (task.plan.skill_key or "").startswith("blinkit_order")
    if not ordering or screen.package != BLINKIT or task.cart == "clear" or len(screen.nodes) < 8:
        return  # other app, done, or still loading
    text = " | ".join(n.label() for n in screen.nodes)
    has_cart = bool(CART_BAR.search(text) or CART_PAGE.search(text))
    if task.cart == "unknown":
        task.cart = "clearing" if has_cart else "clear"
    elif CART_EMPTY.search(text) or not has_cart:
        task.cart = "clear"
        task.history.append("Emptied the old cart")


def _ok(result: dict[str, Any]) -> bool:
    return bool(result.get("ok") or result.get("safety_acknowledgement") or result.get("handed_to_user"))


def absorb_results(task: TaskRecord, results: list[dict[str, Any]]) -> None:
    """History line + learning trace for each executed action."""
    task.last_failed = False
    for r in results:
        sent = task.pending.get(r.get("call_id", ""), {})
        res = r.get("result") or {}
        intent = sent.get("intent") or r.get("name", "")
        if _ok(res):
            task.history.append(intent[:120])
            if sent.get("name") == "open_play_store":
                task.awaiting_install = sent.get("package") or task.plan.target_package
            if sent.get("name") == "wait_for_app":
                task.history[-1] = "The app is installed and open now"
            if sent.get("learn") and sent.get("step"):
                task.trace.append(SkillStep.model_validate(sent["step"]))
        elif res.get("error") not in ("user_declined", "user_changed_plan"):
            task.history.append(f"(failed) {intent[:100]}")
            task.last_failed = True


def _gate(task: TaskRecord, screen: Screen, name: str, args: dict[str, Any], call_id: str) -> GatedAction:
    a = classify(name, args, screen, task.language, task.consented, task.plan.skill_key)
    a.call_id = call_id
    return a


def _remember(task: TaskRecord, screen: Screen, actions: list[GatedAction], learnable: bool) -> None:
    for a in actions:
        step = step_from_action(a.name, {**a.args, "intent": a.intent}, a.intent, screen, task.plan.param_map) if learnable else None
        task.pending[a.call_id] = {
            "name": a.name, "intent": a.intent, "gate": a.gate.value, "package": a.args.get("package"),
            "learn": step is not None, "step": step.model_dump() if step else None,
        }


async def decide(store: Store, task: TaskRecord, screen: Screen) -> StepResponse:
    n = task.turns + task.skill_steps + task.rule_steps

    # 0. Just sent the user to the Play Store: the phone watches for the install itself (no AI).
    if task.awaiting_install:
        pkg, task.awaiting_install = task.awaiting_install, None
        task.rule_steps += 1
        act = _gate(task, screen, "wait_for_app", {"package": pkg, "timeout_s": 180,
                    "intent": phrase(task.language, "waiting_install")}, f"install-{n}")
        _remember(task, screen, [act], learnable=False)
        return StepResponse(actions=[act], status_text=act.intent, source="rule")

    # 1. Free rules for interruptions.
    if (args := interruption_action(screen)) is not None:
        task.rule_steps += 1
        act = _gate(task, screen, "click", args, f"rule-{n}")
        _remember(task, screen, [act], learnable=False)
        return StepResponse(actions=[act], status_text=act.intent, source="rule")

    # 2. Deep link on the very first step (no recipe yet).
    link = task.plan.deep_link or ""
    if not task.history and link.startswith(ALLOWED_LINK_PREFIXES):
        # Rebuilt from each plan (it carries this task's search term), so never part of a recipe.
        task.rule_steps += 1
        app = task.plan.target_app or "the app"
        act = _gate(task, screen, "open_link", {"url": link, "intent": phrase(task.language, "opening_app", app=app)}, f"link-{n}")
        _remember(task, screen, [act], learnable=False)
        return StepResponse(actions=[act], status_text=act.intent, source="rule")

    # Old items in the cart: the AI empties it first (never replayed, never learned).
    track_cart(task, screen)
    if task.cart == "clearing":
        if wants_screenshot(task, screen):
            return StepResponse(need_screenshot=True)
        res = guard_repeats(task, await next_actions(task, screen, CLEAR_CART_HINT))
        _remember(task, screen, res.actions, learnable=False)
        return res

    # 3. Learned skill.
    hint = None
    skill = store.get_skill(task.skill_id) if task.skill_id else None
    if skill is not None:
        skill.steps = generic_steps(skill.steps, task.plan.param_map)  # same recipe attach_skill saw
        if task.replay_index >= len(skill.steps):
            return StepResponse(done=True, message="", source="skill")
        for ahead in range(LOOKAHEAD):
            idx = task.replay_index + ahead
            if idx >= len(skill.steps):
                break
            step = skill.steps[idx]
            args = replay_action(step, screen, task.plan.param_map)
            if args is not None:
                task.replay_index = idx + 1
                task.skill_steps += 1
                act = _gate(task, screen, step.name, args, f"skill-{n}")
                _remember(task, screen, [act], learnable=True)
                return StepResponse(actions=[act], status_text=act.intent, source="skill")
        nxt = skill.steps[task.replay_index]
        hint = nxt.intent or (f"tap '{fill(nxt.label, task.plan.param_map)}'" if nxt.label else nxt.name)

    # 4. Gemini (only when nothing cheaper applies).
    if wants_screenshot(task, screen):
        return StepResponse(need_screenshot=True)
    res = await next_actions(task, screen, hint)
    res = guard_repeats(task, res)
    _remember(task, screen, res.actions, learnable=True)
    # If the AI just did what the recipe expected (same kind of action on a similar element),
    # advance the recipe so replay continues from the next step.
    if skill is not None and res.actions and task.replay_index < len(skill.steps):
        nxt, a0 = skill.steps[task.replay_index], res.actions[0]
        if a0.name == nxt.name:
            tapped = target_node(screen, int(a0.args.get("x", -1)), int(a0.args.get("y", -1))) if "x" in a0.args else None
            if nxt.name == "type" or (tapped is not None and similarity(fill(nxt.label, task.plan.param_map), tapped.label()) >= MIN_MATCH):
                task.replay_index += 1
    return res


def wants_screenshot(task: TaskRecord, screen: Screen) -> bool:
    """The AI needs to see this screen, but the phone sent only the element list."""
    return (not screen.screenshot_b64 and not screen.screenshot_tried
            and needs_screenshot(screen, task.last_failed, first_step=not task.history))


MAX_REPEATS = 2
MAX_WAITS = 5


def guard_repeats(task: TaskRecord, res: StepResponse) -> StepResponse:
    """Stops the agent from looping on the same action (e.g. reopening the same search)."""
    if res.done or not res.actions:
        task.last_signature, task.repeats = "", 0
        return res
    a = res.actions[0]
    sig = f"{a.name}:{sorted((k, str(v)) for k, v in a.args.items() if k in ('x', 'y', 'url', 'text', 'app_name'))}"
    task.repeats = task.repeats + 1 if sig == task.last_signature else 0
    task.last_signature = sig
    # Slow splash screens: a few waits in a row are normal; other repeats mean the agent is stuck.
    limit = MAX_WAITS if a.name == "wait" else MAX_REPEATS
    if task.repeats >= limit:
        log.warning("task %s stopped: action repeated %d times (%s)", task.id, task.repeats + 1, a.name)
        task.status = "failed"
        return StepResponse(done=True, message="", source="ai")
    if task.repeats == 1 and a.name != "wait":
        task.last_failed = True  # next turn: higher thinking + full screenshot + "try another way"
    return res


def attach_skill(store: Store, task: TaskRecord) -> Skill | None:
    """At task start: the user's own recipe for this kind of task, else a shared one."""
    key = task.plan.skill_key
    if not key:
        return None
    if multi_item(task.plan.param_map):
        return None
    skill = store.get_skill(f"{task.user_id}__{key}") or store.get_skill(f"*__{key}")
    if skill:
        skill.steps = generic_steps(skill.steps, task.plan.param_map)
    if skill and len(skill.steps) >= 2 and skill.failures <= skill.successes:
        task.skill_id = skill.doc_id
        return skill
    return None


def finish(store: Store, task: TaskRecord, success: bool) -> None:
    """Learn (or penalise) the skill, and log the task's efficiency."""
    key = task.plan.skill_key
    if key:
        doc_id = task.skill_id or f"{task.user_id}__{key}"
        existing = store.get_skill(doc_id)
        if success:
            skill = learn(task.user_id, key, task.plan.param_map, task.trace, existing if (existing and existing.uid == task.user_id) else None)
            if skill:
                store.put_skill(skill)
        elif existing:
            existing.failures += 1
            store.put_skill(existing)
    log.info(
        "task_finished success=%s skill=%s ai_calls=%d skill_steps=%d rule_steps=%d tokens_in=%d cached=%d tokens_out=%d",
        success, bool(task.skill_id), task.ai_calls, task.skill_steps, task.rule_steps,
        task.input_tokens, task.cached_tokens, task.output_tokens,
    )


def efficiency_note(task: TaskRecord) -> str:
    total = task.ai_calls + task.skill_steps + task.rule_steps
    if task.skill_id:
        return f"Used a saved routine: {task.skill_steps} of {total} steps without AI ({task.ai_calls} AI calls)."
    return f"{task.ai_calls} AI calls, {task.rule_steps} free steps."


__all__ = ["absorb_results", "attach_skill", "decide", "efficiency_note", "family", "finish"]
