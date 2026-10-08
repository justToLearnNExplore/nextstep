"""Wire protocol between the Android executor and the backend.

The phone sends what it sees (accessibility tree + screenshot); the backend answers with
Gemini Computer Use actions, each annotated with a safety gate the phone must honour.
"""

from enum import Enum
from typing import Any, Literal

from pydantic import BaseModel, Field


class Gate(str, Enum):
    AUTO = "auto"  # covered by task-level consent
    CONFIRM = "confirm"  # sensitive / irreversible: ask the user first
    PRIVATE = "private"  # passwords, PINs, OTPs, UPI, biometrics: user does it, agent waits
    BLOCKED = "blocked"  # never allowed

    @property
    def rank(self) -> int:
        return ["auto", "confirm", "private", "blocked"].index(self.value)

    def raise_to(self, other: "Gate") -> "Gate":
        return other if other.rank > self.rank else self


class UiNode(BaseModel):
    i: int
    cls: str | None = None
    text: str | None = None
    desc: str | None = None
    id: str | None = None
    hint: str | None = None
    b: list[int] = Field(default_factory=list, description="[left, top, right, bottom] in px")
    click: bool = False
    edit: bool = False
    scroll: bool = False
    focus: bool = False
    secure: bool = False

    def label(self) -> str:
        return " ".join(x for x in (self.text, self.desc, self.id, self.hint) if x)


class Screen(BaseModel):
    package: str = ""
    width: int
    height: int
    nodes: list[UiNode] = Field(default_factory=list)
    screenshot_b64: str | None = None
    has_secure_field: bool = False

    def nodes_at(self, x_norm: int, y_norm: int) -> list[UiNode]:
        """Nodes under a normalized (0-999) point, ignoring huge containers."""
        x = x_norm * self.width // 1000
        y = y_norm * self.height // 1000
        max_area = self.width * self.height // 4
        hits = []
        for n in self.nodes:
            if len(n.b) != 4:
                continue
            left, top, right, bottom = n.b
            if left <= x <= right and top <= y <= bottom and (right - left) * (bottom - top) <= max_area:
                hits.append(n)
        return hits

    def summary(self, limit: int = 120) -> str:
        """Compact text form of the tree for the model (complements the screenshot)."""
        lines = [f"app={self.package} size={self.width}x{self.height}"]
        for n in self.nodes[:limit]:
            flags = "".join(f for f, on in (("C", n.click), ("E", n.edit), ("S", n.scroll), ("F", n.focus)) if on)
            label = "<secure field>" if n.secure else n.label()
            lines.append(f"[{n.i}] {n.cls or ''} {flags} '{label[:80]}' {n.b}")
        return "\n".join(lines)


# ---- tasks -------------------------------------------------------------------------------


class StartTaskRequest(BaseModel):
    goal: str
    language: str = "en-IN"
    screen: Screen


class TaskPlan(BaseModel):
    """Planner output, written in the user's language, read aloud before consent."""

    summary: str = Field(description="One or two short sentences: what NextStep will do. Ends with a question asking for approval.")
    steps: list[str] = Field(description="3-6 very short steps a senior can follow, in the user's language.")
    target_app: str | None = Field(default=None, description="Main app name, e.g. 'Blinkit', 'WhatsApp', 'YouTube'.")
    target_package: str | None = Field(default=None, description="Android package of the target app if known.")
    sensitive_steps: list[str] = Field(default_factory=list, description="Steps where NextStep will stop and ask (send, order, install, payment).")
    refused: bool = Field(default=False, description="True if the request is unsafe or impossible.")
    operator_goal: str = Field(description="Precise English instruction for the phone operator agent.")


class StartTaskResponse(BaseModel):
    task_id: str
    plan: TaskPlan


class ConsentRequest(BaseModel):
    approved: bool


class ActionResult(BaseModel):
    call_id: str
    name: str
    result: dict[str, Any] = Field(default_factory=dict)


class StepRequest(BaseModel):
    screen: Screen
    results: list[ActionResult] = Field(default_factory=list)


class GatedAction(BaseModel):
    call_id: str
    name: str
    args: dict[str, Any] = Field(default_factory=dict)
    intent: str = ""
    gate: Gate
    reason: str = ""
    ask: str = Field(default="", description="What to say to the user when gate is confirm/private/blocked.")
    yes_label: str = ""


class StepResponse(BaseModel):
    actions: list[GatedAction] = Field(default_factory=list)
    done: bool = False
    message: str = ""
    status_text: str = ""
    done_steps: list[str] = Field(default_factory=list)


# ---- screen explanation ------------------------------------------------------------------


class ExplainRequest(BaseModel):
    language: str = "en-IN"
    screen: Screen


class ExplainOption(BaseModel):
    id: Literal["back", "home", "close", "continue"]
    label: str
    recommended: bool = False


class ScreenExplanation(BaseModel):
    explanation: str = Field(description="2-3 short, calm sentences in the user's language: what this screen is and what is safe to do.")
    risk: Literal["none", "caution", "danger"] = "none"
    options: list[ExplainOption] = Field(description="2-3 options. Recommend the safest one.")


# ---- scam shield -------------------------------------------------------------------------


class ScamCheckRequest(BaseModel):
    language: str = "en-IN"
    source: str = ""
    sender: str = ""
    text: str


class ScamVerdict(BaseModel):
    risk: Literal["none", "low", "medium", "high"]
    reasons: list[str] = Field(default_factory=list, description="Short English reason codes.")
    impersonates: str | None = Field(default=None, description="Organisation the message pretends to be, if any (e.g. 'SBI', 'India Post').")
    warning: str = Field(default="", description="Calm 1-2 sentence warning in the user's language. Must say not to open links or share details when risky.")


class ScamCheckResponse(ScamVerdict):
    official_url: str | None = None
    official_label: str | None = None
