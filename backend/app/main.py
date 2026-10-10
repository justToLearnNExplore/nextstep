"""NextStep backend (Cloud Run). Orchestrates the ADK agents and Gemini Computer Use, and serves
the family timeline."""

import logging
import time

from fastapi import Depends, FastAPI, Header, HTTPException
from starlette.types import ASGIApp, Receive, Scope, Send

from . import family
from .agents.explainer import explain_screen
from .agents.medicine_reader import read_medicine
from .agents.planner import plan_task
from .agents.scam_shield import check_message
from .auth import current_user
from .config import settings
from .operator import next_actions
from .protocol import (
    ClientEvent,
    ConsentRequest,
    ExplainRequest,
    FeedEvent,
    FeedResponse,
    InviteResponse,
    JoinRequest,
    JoinResponse,
    MedicineInfo,
    MedicineRequest,
    ProfileResponse,
    ProfileUpdate,
    ScamCheckRequest,
    ScamCheckResponse,
    ScreenExplanation,
    StartTaskRequest,
    StartTaskResponse,
    StepRequest,
    StepResponse,
)
from .store import Profile, TaskRecord, make_store

logging.basicConfig(level=logging.INFO)
log = logging.getLogger("nextstep")

app = FastAPI(title="NextStep", version="0.2.0")
store = make_store()


class StripApiPrefix:
    """Firebase Hosting forwards /api/** to this service unchanged; serve those as /**."""

    def __init__(self, inner: ASGIApp) -> None:
        self.inner = inner

    async def __call__(self, scope: Scope, receive: Receive, send: Send) -> None:
        if scope["type"] == "http" and scope["path"].startswith("/api/"):
            scope = dict(scope, path=scope["path"][4:], raw_path=scope["path"][4:].encode())
        await self.inner(scope, receive, send)


app.add_middleware(StripApiPrefix)


def senior(uid: str = Depends(current_user)) -> str:
    """Current senior; keeps "last active" fresh for the family view (at most once a minute)."""
    p = store.get_profile(uid)
    now = time.time()
    if p is None:
        store.put_profile(Profile(uid=uid))
    elif now - p.last_seen > 60:
        p.last_seen = now
        store.put_profile(p)
    return uid


def _task(task_id: str, user: str) -> TaskRecord:
    task = store.get(task_id)
    if task is None or task.user_id != user:
        raise HTTPException(404, "Task not found")
    return task


@app.get("/healthz")
def healthz() -> dict[str, str]:
    return {"status": "ok"}


# ---- profile -----------------------------------------------------------------------------


@app.put("/v1/me", response_model=ProfileResponse)
def update_me(req: ProfileUpdate, uid: str = Depends(senior)) -> ProfileResponse:
    p = store.get_profile(uid) or Profile(uid=uid)
    p.display_name, p.language = req.display_name.strip(), req.language
    store.put_profile(p)
    return get_me(uid)


@app.get("/v1/me", response_model=ProfileResponse)
def get_me(uid: str = Depends(senior)) -> ProfileResponse:
    p = store.get_profile(uid) or Profile(uid=uid)
    viewers = [v.name or "Family member" for v in store.list_viewers(uid) if not v.revoked]
    return ProfileResponse(display_name=p.display_name, language=p.language, viewers=viewers)


# ---- tasks -------------------------------------------------------------------------------


@app.post("/v1/tasks", response_model=StartTaskResponse)
async def start_task(req: StartTaskRequest, user: str = Depends(senior)) -> StartTaskResponse:
    plan = await plan_task(req.goal, req.language, req.screen)
    task = TaskRecord(user_id=user, goal=req.goal, language=req.language, plan=plan)
    task.add_log("plan", summary=plan.summary, steps=plan.steps, refused=plan.refused)
    store.put(task)
    return StartTaskResponse(task_id=task.id, plan=plan)


@app.post("/v1/tasks/{task_id}/consent")
def consent(task_id: str, req: ConsentRequest, user: str = Depends(senior)) -> dict[str, str]:
    task = _task(task_id, user)
    if task.plan.refused:
        raise HTTPException(409, "Refused tasks cannot be approved")
    task.consented = req.approved
    task.status = "running" if req.approved else "declined"
    task.add_log("consent", approved=req.approved)
    family.on_consent(store, task, req.approved)
    store.put(task)
    return {"status": task.status}


@app.post("/v1/tasks/{task_id}/step", response_model=StepResponse)
async def step(task_id: str, req: StepRequest, user: str = Depends(senior)) -> StepResponse:
    task = _task(task_id, user)
    if task.status != "running" or not task.consented:
        raise HTTPException(409, f"Task is {task.status}")

    results = [r.model_dump() for r in req.results]
    for r in results:
        task.add_log("result", name=r["name"], call_id=r["call_id"], result=r["result"])
    family.on_results(store, task, results)

    if task.turns >= settings.max_turns:
        task.status = "failed"
        res = StepResponse(done=True, message="")
    else:
        res = await next_actions(task, req.screen, results)
    for a in res.actions:
        task.add_log("action", name=a.name, intent=a.intent, gate=a.gate.value, reason=a.reason)
    family.on_actions(task, res.actions)
    if res.done:
        task.add_log("done", message=res.message)
        family.on_finished(store, task, res.message)
    store.put(task)
    return res


@app.post("/v1/tasks/{task_id}/stop")
def stop(task_id: str, user: str = Depends(senior)) -> dict[str, str]:
    task = _task(task_id, user)
    if task.status == "running":
        family.on_stopped(store, task)
    task.status = "stopped"
    task.add_log("stop")
    store.put(task)
    return {"status": "stopped"}


@app.get("/v1/tasks/{task_id}")
def get_task(task_id: str, user: str = Depends(senior)) -> TaskRecord:
    return _task(task_id, user)


# ---- helpers on the phone ----------------------------------------------------------------


@app.post("/v1/screen/explain", response_model=ScreenExplanation)
async def explain(req: ExplainRequest, user: str = Depends(senior)) -> ScreenExplanation:
    res = await explain_screen(req.language, req.screen)
    family.on_explain(store, user, res)
    return res


@app.post("/v1/scam/check", response_model=ScamCheckResponse)
async def scam_check(req: ScamCheckRequest, user: str = Depends(senior)) -> ScamCheckResponse:
    res = await check_message(req)
    log.info("scam_check user=%s risk=%s reasons=%s", user, res.risk, res.reasons)
    family.on_scam(store, user, res)
    return res


@app.post("/v1/medicine/read", response_model=MedicineInfo)
async def medicine_read(req: MedicineRequest, user: str = Depends(senior)) -> MedicineInfo:
    # The photo is processed in memory only; it is never stored server-side.
    info = await read_medicine(req)
    family.on_medicine(store, user, info)
    return info


@app.post("/v1/events")
def client_event(req: ClientEvent, user: str = Depends(senior)) -> dict[str, str]:
    """Outcomes only the phone knows (e.g. the medicine photo was sent after a yes)."""
    if req.kind not in family.CLIENT_EVENTS:
        raise HTTPException(400, "Unknown event kind")
    family.on_client_event(store, user, req.kind, req.data)
    return {"status": "ok"}


# ---- family sharing (no sign-up) ---------------------------------------------------------


@app.post("/v1/family/invite", response_model=InviteResponse)
def invite(uid: str = Depends(senior)) -> InviteResponse:
    inv = family.create_invite(store, uid)
    return InviteResponse(code=inv.code, join_url=f"{settings.dashboard_url}/join?code={inv.code}", expires_at=inv.expires_at)


@app.post("/v1/family/revoke")
def revoke(uid: str = Depends(senior)) -> dict[str, int]:
    return {"revoked": family.revoke_all(store, uid)}


@app.post("/v1/family/join", response_model=JoinResponse)
def join(req: JoinRequest) -> JoinResponse:
    redeemed = family.redeem_invite(store, req.code, req.viewer_name)
    if redeemed is None:
        raise HTTPException(404, "This link has expired or was already used. Ask for a new one.")
    token, viewer = redeemed
    p = store.get_profile(viewer.uid)
    return JoinResponse(viewer_token=token, senior_name=p.display_name if p else "")


@app.get("/v1/family/feed", response_model=FeedResponse)
def feed(limit: int = 100, x_viewer_token: str | None = Header(default=None)) -> FeedResponse:
    viewer = family.viewer_for(store, x_viewer_token or "")
    if viewer is None:
        raise HTTPException(401, "Sharing was stopped or this device is not linked.")
    p = store.get_profile(viewer.uid) or Profile(uid=viewer.uid)
    events = store.list_events(viewer.uid, max(1, min(limit, 300)))
    return FeedResponse(
        senior_name=p.display_name,
        language=p.language,
        last_seen=p.last_seen,
        events=[FeedEvent(**e.model_dump(exclude={"task_id"})) for e in events],
    )
