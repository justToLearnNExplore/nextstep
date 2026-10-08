"""Guardian: deterministic safety gate for every action the operator proposes.

Runs on the server before an action reaches the phone; the phone's SafetyGate re-checks it
against the live screen and can only escalate. Model signals (Computer Use safety_decision)
can raise a gate but never lower it.
"""

import re
from typing import Any
from urllib.parse import urlparse

from .i18n import phrase
from .protocol import Gate, GatedAction, Screen
from .official_sites import is_official_domain

PAYMENT_APPS = {
    "com.phonepe.app",
    "net.one97.paytm",
    "com.google.android.apps.nbu.paisa.user",
    "in.org.npci.upiapp",
    "in.amazon.mShop.android.shopping.upi",
}
PAYMENT_APP_NAMES = re.compile(r"\b(phonepe|paytm|google pay|gpay|bhim)\b", re.I)
MESSAGE_APPS = {"com.whatsapp", "com.whatsapp.w4b", "com.google.android.apps.messaging", "com.android.mms"}

# Keep in sync with SafetyGate.kt (on-device copy).
IRREVERSIBLE = re.compile(
    r"(place order|confirm order|order now|\bpay\b|proceed to pay|make payment|\bsend\b|\bshare\b|"
    r"install|uninstall|delete|remove account|transfer|buy now|submit|add address|save address|"
    r"भेजें|भेजो|ऑर्डर करें|भुगतान|इंस्टॉल|हटाएं|"
    r"ಕಳುಹಿಸು|ಕಳುಹಿಸಿ|ಆರ್ಡರ್ ಮಾಡಿ|ಪಾವತಿ|ಸ್ಥಾಪಿಸು)",
    re.I,
)
SECRET = re.compile(
    r"(password|passcode|\bpin\b|mpin|upi pin|\botp\b|one.time|verification code|cvv|cvc|card number|"
    r"expiry|aadhaar|पासवर्ड|पिन|ओटीपी|ಪಾಸ್‌ವರ್ಡ್|ಪಿನ್|ಒಟಿಪಿ)",
    re.I,
)
URL = re.compile(r"(https?://\S+|\b[\w-]+\.(?:com|in|net|org|co|xyz|top|info|link|ly|me)\S*)", re.I)
DIGITS_ONLY = re.compile(r"^\s*\d{4,8}\s*$")


def classify(
    name: str,
    args: dict[str, Any],
    screen: Screen,
    language: str,
    consented: bool,
) -> GatedAction:
    """Returns the action with gate, reason and the sentence to say to the user."""
    intent = str(args.get("intent", ""))
    clean_args = {k: v for k, v in args.items() if k not in ("intent", "safety_decision")}
    action = GatedAction(call_id="", name=name, args=clean_args, intent=intent, gate=Gate.AUTO, reason="routine")

    def escalate(gate: Gate, reason: str, ask: str = "") -> None:
        if gate.rank > action.gate.rank:
            action.gate, action.reason = gate, reason
            if ask:
                action.ask = ask

    if not consented:
        escalate(Gate.BLOCKED, "no_task_consent", phrase(language, "no_consent"))
        return action

    # Inside a payment app everything is the user's to do.
    if screen.package in PAYMENT_APPS:
        escalate(Gate.PRIVATE, "payment_app", phrase(language, "payment_app"))

    if name == "open_app" and PAYMENT_APP_NAMES.search(str(args.get("app_name", ""))):
        escalate(Gate.PRIVATE, "payment_app", phrase(language, "payment_app"))

    if name == "open_play_store":
        app = str(args.get("app_name") or args.get("package", ""))
        escalate(Gate.CONFIRM, "install_app", phrase(language, "install", app=app))
        action.yes_label = phrase(language, "yes_go")

    if name == "hand_over_to_user":
        escalate(Gate.PRIVATE, "user_only_step", str(args.get("reason") or phrase(language, "private")))

    if name in ("click", "long_press") and "x" in args and "y" in args:
        hits = screen.nodes_at(int(args["x"]), int(args["y"]))
        label = " ".join(n.label() for n in hits)
        if any(n.secure for n in hits) or (any(n.edit for n in hits) and SECRET.search(label)):
            escalate(Gate.PRIVATE, "secret_field", phrase(language, "private"))
        elif screen.package in MESSAGE_APPS and _has_unofficial_link(label):
            escalate(Gate.BLOCKED, "unverified_link_in_message", phrase(language, "blocked_link"))
        elif IRREVERSIBLE.search(label) or IRREVERSIBLE.search(intent):
            escalate(Gate.CONFIRM, f"irreversible:{label[:60] or intent[:60]}")

    if name == "type":
        focused = next((n for n in screen.nodes if n.focus and n.edit), None)
        if (focused and (focused.secure or SECRET.search(focused.label()))) or (
            screen.has_secure_field and DIGITS_ONLY.match(str(args.get("text", "")))
        ):
            escalate(Gate.PRIVATE, "secret_field", phrase(language, "private"))

    # Gemini Computer Use's own policy engine (payments, messaging, account creation, ...).
    decision = args.get("safety_decision") or {}
    if isinstance(decision, dict) and decision.get("decision") == "require_confirmation":
        escalate(Gate.CONFIRM, "model_safety:" + str(decision.get("explanation", ""))[:80])

    if action.gate == Gate.CONFIRM and not action.ask:
        action.ask = f"{intent} {phrase(language, 'confirm_suffix')}".strip()
        action.yes_label = action.yes_label or phrase(language, "yes_go")
    return action


def _has_unofficial_link(label: str) -> bool:
    for match in URL.findall(label):
        url = match if match.lower().startswith("http") else f"https://{match}"
        host = (urlparse(url).hostname or "").lower()
        if host and not is_official_domain(host):
            return True
    return False
