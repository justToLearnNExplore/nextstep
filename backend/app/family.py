"""Family timeline: turns agent activity into plain-language events, and the no-sign-up sharing
model (single-use invite code → long-lived viewer token the senior can revoke).

What family members can see: what NextStep did, what it asked, and what the senior decided.
What they never see: screenshots, photos, message texts, passwords, OTPs or PINs.
"""

import secrets
import time
from typing import Any

from .protocol import MedicineInfo, ScamCheckResponse, ScreenExplanation
from .store import FamilyEvent, Invite, Store, TaskRecord, Viewer, hash_token, new_invite_code

INVITE_TTL_S = 15 * 60

_REASONS = {
    "lookalike_domain": "a fake look-alike website",
    "unverified_link": "an unverified link",
    "urgency": "urgent or threatening language",
    "asks_sensitive_info": "a request for OTP, PIN or KYC details",
    "money_bait": "a money or prize offer",
}


def _name(store: Store, uid: str) -> str:
    p = store.get_profile(uid)
    return p.display_name if p and p.display_name else "They"


def record(store: Store, uid: str, kind: str, severity: str, title: str, detail: str = "", task_id: str | None = None) -> None:
    store.add_event(uid, FamilyEvent(kind=kind, severity=severity, title=title, detail=detail, task_id=task_id))  # type: ignore[arg-type]


# ---- task lifecycle ----------------------------------------------------------------------


def on_consent(store: Store, task: TaskRecord, approved: bool) -> None:
    name = _name(store, task.user_id)
    if approved:
        record(store, task.user_id, "task_started", "routine", task.goal, f"{name} approved the plan.", task.id)
    else:
        record(store, task.user_id, "task_declined", "declined", task.goal, f"{name} said no to the plan.", task.id)


def on_results(store: Store, task: TaskRecord, results: list[dict[str, Any]]) -> None:
    """Confirmations, refusals and private hand-overs come back as action results."""
    name = _name(store, task.user_id)
    for r in results:
        sent = task.pending.pop(r.get("call_id", ""), None) or {}
        res = r.get("result") or {}
        intent = sent.get("intent") or r.get("name", "")
        if res.get("safety_acknowledgement"):
            record(store, task.user_id, "confirmed", "confirmed", intent, f"{name} said yes", task.id)
        elif res.get("error") == "user_declined":
            record(store, task.user_id, "declined", "declined", intent, f"{name} said no, so NextStep did not do it", task.id)
        elif res.get("handed_to_user"):
            record(store, task.user_id, "private", "private", f"{name} completed a private step",
                   "NextStep paused and never saw it (password, OTP or payment).", task.id)
        elif res.get("error") == "blocked_by_safety":
            record(store, task.user_id, "blocked", "alert", "NextStep refused an unsafe step", intent, task.id)


def on_actions(task: TaskRecord, actions: list[Any]) -> None:
    for a in actions:
        task.pending[a.call_id] = {"name": a.name, "intent": a.intent, "gate": a.gate.value}


def on_finished(store: Store, task: TaskRecord, message: str) -> None:
    if task.status == "failed":
        record(store, task.user_id, "task_failed", "failed", f"Couldn't finish: {task.goal}", message, task.id)
    else:
        record(store, task.user_id, "task_done", "done", f"Finished: {task.goal}", message, task.id)


def on_stopped(store: Store, task: TaskRecord) -> None:
    record(store, task.user_id, "task_stopped", "declined", f"{_name(store, task.user_id)} stopped a task", task.goal, task.id)


# ---- other features ----------------------------------------------------------------------


def on_scam(store: Store, uid: str, res: ScamCheckResponse) -> None:
    if res.risk not in ("medium", "high"):
        return
    why = sorted({_REASONS[r.split(":")[0]] for r in res.reasons if r.split(":")[0] in _REASONS})
    detail = f"Pretended to be {res.impersonates}." if res.impersonates else "Suspicious message."
    if why:
        detail += " Signs: " + ", ".join(why) + "."
    detail += f" {_name(store, uid)} was warned."
    if res.official_label:
        detail += f" NextStep offered the official {res.official_label} website instead."
    record(store, uid, "scam_blocked", "alert", "Scam message stopped", detail)


def on_explain(store: Store, uid: str, res: ScreenExplanation) -> None:
    if res.risk == "danger":
        record(store, uid, "risky_screen", "alert", "Explained a risky screen", res.explanation)
    elif res.risk == "caution":
        record(store, uid, "screen_explained", "routine", "Explained a screen", res.explanation)


def on_medicine(store: Store, uid: str, info: MedicineInfo) -> None:
    if not info.readable:
        return
    bits = [b for b in (info.name, f"({info.generic})" if info.generic else None) if b]
    detail = " ".join(bits)
    if info.expiry:
        detail += f" · expires {info.expiry}"
    record(store, uid, "medicine_read", "alert" if info.expired else "routine",
           "Medicine may be expired" if info.expired else "Read a medicine label", detail)


CLIENT_EVENTS = {"share_sent", "share_declined"}


def on_client_event(store: Store, uid: str, kind: str, data: dict[str, Any]) -> None:
    name = _name(store, uid)
    recipient = str(data.get("recipient", "")).strip()[:60] or "their contact"
    if kind == "share_sent":
        record(store, uid, kind, "confirmed", f"Sent medicine photo to {recipient} on WhatsApp", f"{name} said yes")
    elif kind == "share_declined":
        record(store, uid, kind, "declined", f"Medicine photo not sent to {recipient}", f"{name} said no")


# ---- sharing -----------------------------------------------------------------------------


def create_invite(store: Store, uid: str) -> Invite:
    inv = Invite(code=new_invite_code(), uid=uid, expires_at=time.time() + INVITE_TTL_S)
    store.put_invite(inv)
    return inv


def redeem_invite(store: Store, code: str, viewer_name: str) -> tuple[str, Viewer] | None:
    code = code.replace("-", "").replace(" ", "").upper()
    inv = store.get_invite(code)
    if inv is None or inv.used or inv.expires_at < time.time():
        return None
    inv.used = True
    store.put_invite(inv)
    token = secrets.token_urlsafe(32)
    viewer = Viewer(token_hash=hash_token(token), uid=inv.uid, name=viewer_name.strip()[:40])
    store.put_viewer(viewer)
    record(store, inv.uid, "viewer_added", "routine", f"{viewer.name or 'A family member'} can now see this timeline")
    return token, viewer


def viewer_for(store: Store, token: str) -> Viewer | None:
    v = store.get_viewer(hash_token(token))
    return v if v and not v.revoked else None


def revoke_all(store: Store, uid: str) -> int:
    n = 0
    for v in store.list_viewers(uid):
        if not v.revoked:
            v.revoked = True
            store.put_viewer(v)
            n += 1
    record(store, uid, "sharing_stopped", "routine", "Stopped sharing with family")
    return n
