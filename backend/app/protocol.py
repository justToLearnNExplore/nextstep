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

    def summary(self, limit: int = 80) -> str:
        """Compact text form of the screen for the model: only elements a person could read or
        tap, short labels, bounds normalised to 0-999 (same space as Computer Use clicks)."""
        lines = [f"app={self.package}"]
        seen: set[str] = set()
        for n in self.nodes:
            if len(lines) > limit:
                break
            label = "<secure field>" if n.secure else n.label()[:50]
            if not (label or n.click or n.edit):
                continue
            box = self.norm_box(n)
            key = f"{label}|{box[:2]}"
            if key in seen:  # nested containers often repeat the same label
                continue
            seen.add(key)
            flags = "".join(f for f, on in (("tap", n.click), ("input", n.edit), ("scroll", n.scroll)) if on)
            lines.append(f"- '{label}' {flags} @{box[0]},{box[1]}")
        return "\n".join(lines)

    def norm_box(self, n: UiNode) -> tuple[int, int]:
        """Centre of a node in Computer Use's 0-999 coordinate space."""
        if len(n.b) != 4 or not self.width or not self.height:
            return (0, 0)
        cx = (n.b[0] + n.b[2]) / 2 * 1000 / self.width
        cy = (n.b[1] + n.b[3]) / 2 * 1000 / self.height
        return (min(999, int(cx)), min(999, int(cy)))

    def text_chars(self) -> int:
        return sum(len(n.label()) for n in self.nodes if not n.secure)


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
    skill_key: str | None = Field(
        default=None,
        description="Stable snake_case id for this KIND of task, independent of item/person, e.g. 'blinkit_order_item', 'youtube_play_search', 'whatsapp_call_contact'.",
    )
    params: dict[str, str] = Field(
        default_factory=dict, description="The variable parts, e.g. {'item': 'milk 1 litre'} or {'query': 'devotional songs'}."
    )
    deep_link: str | None = Field(
        default=None,
        description="Optional https link that jumps straight to the right screen, only from: youtube.com/results?search_query=..., blinkit.com/s/?q=...",
    )
    special_flow: Literal["medicine_photo"] | None = Field(
        default=None, description="'medicine_photo' when the user wants to photograph a medicine/prescription and share it."
    )
    recipient: Literal["doctor", "family"] | None = Field(
        default=None, description="For medicine_photo: who to send it to, if the user said so."
    )
    operator_goal: str = Field(description="Precise English instruction for the phone operator agent.")


class MedicineRequest(BaseModel):
    language: str = "en-IN"
    image_b64: str
    ocr_text: str = ""
    recipient: Literal["doctor", "family"] = "doctor"
    sender_name: str | None = None


class MedicineInfo(BaseModel):
    """What is on the label, read from the photo. Identification only, never medical advice."""

    readable: bool = Field(description="False if the label cannot be read reliably.")
    name: str | None = Field(default=None, description="Brand name as printed, e.g. 'Dolo 650'.")
    generic: str | None = Field(default=None, description="Active ingredient(s) and strength, e.g. 'Paracetamol 650 mg'.")
    form: str | None = Field(default=None, description="tablet, syrup, capsule, injection, ointment...")
    expiry: str | None = Field(default=None, description="Expiry as printed, e.g. '08/2027'.")
    expired: bool | None = Field(default=None, description="True only if the printed expiry is clearly before today's date.")
    say_to_user: str = Field(description="1-2 short sentences in the user's language: what medicine this looks like; mention if expired or unreadable.")
    caption: str = Field(description="WhatsApp caption to the recipient in the user's language, written as the user (first person), e.g. asking the doctor to check this medicine.")


class StartTaskResponse(BaseModel):
    task_id: str
    plan: TaskPlan
    uses_saved_routine: bool = False


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
    source: Literal["ai", "skill", "rule", "none"] = "none"
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


# ---- profile & family sharing ------------------------------------------------------------


class ProfileUpdate(BaseModel):
    display_name: str = Field(min_length=1, max_length=40)
    language: str = "en-IN"


class ProfileResponse(BaseModel):
    display_name: str
    language: str
    viewers: list[str] = Field(default_factory=list, description="Names of family members who can see the timeline.")


class InviteResponse(BaseModel):
    code: str
    join_url: str
    expires_at: float


class JoinRequest(BaseModel):
    code: str
    viewer_name: str = ""


class JoinResponse(BaseModel):
    viewer_token: str
    senior_name: str


class FeedEvent(BaseModel):
    id: str
    at: float
    kind: str
    severity: str
    title: str
    detail: str = ""


class FeedResponse(BaseModel):
    senior_name: str
    language: str
    last_seen: float
    events: list[FeedEvent]


class ClientEvent(BaseModel):
    kind: str
    data: dict[str, Any] = Field(default_factory=dict)


# ---- speech -------------------------------------------------------------------------------


class SttRequest(BaseModel):
    language: str = "en-IN"
    audio_b64: str = Field(description="Mono 16-bit little-endian PCM (LINEAR16).")
    sample_rate: int = 16000


class SttResponse(BaseModel):
    text: str
