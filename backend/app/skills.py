"""Learned skills: Hermes-style procedural memory for repetitive tasks.

The first time a senior orders milk, Gemini Computer Use works it out step by step (~20 calls).
When that task succeeds, we keep the *recipe*: each action recorded against the element's label
("tap 'Search'", "type {item}", "tap 'Add' on 'Amul Taaza'"), never raw coordinates. The next
"order milk" (or "order bread": same skill, new params) is replayed by matching labels on the live
screen, with zero model calls. If the app shows something unexpected, Gemini handles just that
step and replay continues. Every replayed action still goes through the Guardian and the phone's
SafetyGate, so "Place order" always asks.
"""

import re
import time
from typing import Any

from pydantic import BaseModel, Field

from .protocol import Screen, UiNode

REPLAYABLE = {"click", "long_press", "type", "go_back", "press_key", "open_app", "open_link", "wait"}
MIN_MATCH = 0.6


class SkillStep(BaseModel):
    name: str
    package: str = ""
    label: str = ""  # label of the element acted on (taps), as seen when learned
    context: str = ""  # nearest descriptive text, to tell apart repeated buttons ("ADD" under which product)
    text: str = ""  # typed text; may contain {param} placeholders
    args: dict[str, Any] = Field(default_factory=dict)  # non-positional args (key, app_name, url)
    intent: str = ""


class Skill(BaseModel):
    key: str  # e.g. "blinkit_order_item"
    uid: str  # owner; "*" for shared skills
    steps: list[SkillStep]
    params: list[str] = Field(default_factory=list)
    successes: int = 1
    failures: int = 0
    updated_at: float = Field(default_factory=time.time)

    @property
    def doc_id(self) -> str:
        return f"{self.uid}__{self.key}"


# ---- learning ----------------------------------------------------------------------------


def step_from_action(name: str, args: dict[str, Any], intent: str, screen: Screen, params: dict[str, str]) -> SkillStep | None:
    """Turns an executed action into a coordinate-free recipe step."""
    if name not in REPLAYABLE:
        return None
    step = SkillStep(name=name, package=screen.package, intent=intent)
    if name in ("click", "long_press"):
        node = target_node(screen, int(args.get("x", -1)), int(args.get("y", -1)))
        if node is None or not node.label():
            return None  # can't be replayed by label (e.g. tapped a blank area)
        step.label = templatize(node.label(), params)
        if ctx := context_of(screen, node):
            step.context = templatize(ctx, params)
    elif name == "type":
        step.text = templatize(str(args.get("text", "")), params)
        step.args = {"press_enter": bool(args.get("press_enter", False))}
    else:
        step.args = {k: v for k, v in args.items() if k in ("key", "app_name", "url", "seconds")}
    return step


def target_node(screen: Screen, x: int, y: int) -> UiNode | None:
    """The smallest labelled element under a 0-999 point."""
    hits = [n for n in screen.nodes_at(x, y) if n.label() and not n.secure]
    if not hits:
        return None
    return min(hits, key=lambda n: (n.b[2] - n.b[0]) * (n.b[3] - n.b[1]) if len(n.b) == 4 else 1 << 30)


GENERIC = re.compile(r"^(add|\+|buy|ok|go|next|select|open|view|more|यहाँ|जोड़ें|ಸೇರಿಸಿ)$", re.I)


def _centre(n: UiNode) -> tuple[float, float]:
    return ((n.b[0] + n.b[2]) / 2, (n.b[1] + n.b[3]) / 2) if len(n.b) == 4 else (0.0, 0.0)


def context_of(screen: Screen, node: UiNode) -> str:
    """For short/generic buttons, the closest descriptive text (e.g. the product name above 'ADD')."""
    if len(node.label()) > 12 and not GENERIC.match(node.label().strip()):
        return ""
    cx, cy = _centre(node)
    best, best_d = "", float("inf")
    for n in screen.nodes:
        lbl = n.label()
        if n is node or not lbl or n.secure or GENERIC.match(lbl.strip()) or len(lbl) < 4:
            continue
        x, y = _centre(n)
        d = ((x - cx) ** 2 + (y - cy) ** 2) ** 0.5
        if d < best_d and abs(y - cy) < screen.height * 0.2:
            best, best_d = lbl, d
    return best[:80]


def templatize(text: str, params: dict[str, str]) -> str:
    """'Amul milk 1 litre' with params {item: 'milk'} -> 'Amul {item} 1 litre' (case-insensitive)."""
    for k, v in sorted(params.items(), key=lambda kv: -len(kv[1])):
        if v and len(v) >= 2:
            text = re.sub(re.escape(v), "{" + k + "}", text, flags=re.I)
    return text


def fill(text: str, params: dict[str, str]) -> str:
    return re.sub(r"\{(\w+)\}", lambda m: params.get(m.group(1), m.group(0)), text)


def learn(uid: str, key: str, params: dict[str, str], trace: list[SkillStep], existing: Skill | None) -> Skill | None:
    """Builds or reinforces a skill from a successful run's trace."""
    steps = [s for s in trace if s.name in REPLAYABLE]
    if len(steps) < 2:
        return existing
    if existing and len(existing.steps) <= len(steps) and existing.successes >= 2:
        existing.successes += 1  # keep the proven (shorter) recipe
        existing.updated_at = time.time()
        return existing
    return Skill(key=key, uid=uid, steps=steps, params=sorted(params), successes=(existing.successes + 1) if existing else 1)


# ---- replay ------------------------------------------------------------------------------


def _tokens(s: str) -> set[str]:
    return {t for t in re.findall(r"[\w₹]+", s.lower()) if len(t) > 1 or t.isdigit()}


def similarity(a: str, b: str) -> float:
    ta, tb = _tokens(a), _tokens(b)
    if not ta or not tb:
        return 0.0
    if a.strip().lower() == b.strip().lower():
        return 1.0
    return len(ta & tb) / len(ta | tb) * 0.7 + (0.3 if (a.lower() in b.lower() or b.lower() in a.lower()) else 0.0)


def find_by_label(screen: Screen, label: str, context: str = "") -> UiNode | None:
    """Best visible element for a recipe label; prefers tappable ones, and when the recipe
    remembered a context ("Amul Taaza ... 1 L"), the candidate nearest to matching text."""
    candidates = []
    for n in screen.nodes:
        if n.secure or not n.label():
            continue
        score = similarity(label, n.label()) + (0.05 if n.click else 0)
        if score >= MIN_MATCH:
            candidates.append((score, n))
    if not candidates:
        return None
    if context and len(candidates) > 1:
        anchors = [n for n in screen.nodes if n.label() and similarity(context, n.label()) >= 0.5]
        if not anchors:
            return None  # the item isn't on this screen; let the AI look (e.g. scroll/search)
        def dist(n: UiNode) -> float:
            cx, cy = _centre(n)
            return min(((cx - _centre(a)[0]) ** 2 + (cy - _centre(a)[1]) ** 2) ** 0.5 for a in anchors)
        return min(candidates, key=lambda sn: dist(sn[1]))[1]
    return max(candidates, key=lambda sn: sn[0])[1]


def replay_action(step: SkillStep, screen: Screen, params: dict[str, str]) -> dict[str, Any] | None:
    """The concrete Computer Use action for this recipe step on the current screen, or None if
    the screen doesn't match (then the AI handles this step)."""
    if step.package and screen.package and step.package != screen.package and step.name not in ("open_app", "open_link"):
        return None
    if step.name in ("click", "long_press"):
        node = find_by_label(screen, fill(step.label, params), fill(step.context, params))
        if node is None:
            return None
        x, y = screen.norm_box(node)
        return {"x": x, "y": y, "intent": step.intent or f"Tap '{node.label()[:40]}'"}
    if step.name == "type":
        if not any(n.focus and n.edit for n in screen.nodes):
            return None
        return {"text": fill(step.text, params), "press_enter": step.args.get("press_enter", False), "intent": step.intent}
    return {**step.args, "intent": step.intent}
