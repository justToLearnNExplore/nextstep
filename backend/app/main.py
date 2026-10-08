"""NextStep backend (Cloud Run). Orchestrates the ADK agents and Gemini Computer Use."""

import logging

from fastapi import Depends, FastAPI, HTTPException

from .agents.explainer import explain_screen
from .agents.medicine_reader import read_medicine
from .agents.planner import plan_task
from .agents.scam_shield import check_message
from .auth import current_user
from .config import settings
from .operator import next_actions
from .protocol import (
    ConsentRequest,
    ExplainRequest,
    MedicineInfo,
    MedicineRequest,
    ScamCheckRequest,
    ScamCheckResponse,
    ScreenExplanation,
    StartTaskRequest,
    StartTaskResponse,
    StepRequest,
    StepResponse,
)
from .store import TaskRecord, make_store

logging.basicConfig(level=logging.INFO)
log = logging.getLogger("nextstep")

app = FastAPI(title="NextStep", version="0.1.0")
store = make_store()


def _task(task_id: str, user: str) -> TaskRecord:
    task = store.get(task_id)
    if task is None or task.user_id != user:
        raise HTTPException(404, "Task not found")
    return task


@app.get("/healthz")
def healthz() -> dict[str, str]:
    return {"status": "ok"}


@app.post("/v1/tasks", response_model=StartTaskResponse)
async def start_task(req: StartTaskRequest, user: str = Depends(current_user)) -> StartTaskResponse:
    plan = await plan_task(req.goal, req.language, req.screen)
    task = TaskRecord(user_id=user, goal=req.goal, language=req.language, plan=plan)
    task.add_log("plan", summary=plan.summary, steps=plan.steps, refused=plan.refused)
    store.put(task)
    return StartTaskResponse(task_id=task.id, plan=plan)


@app.post("/v1/tasks/{task_id}/consent")
def consent(task_id: str, req: ConsentRequest, user: str = Depends(current_user)) -> dict[str, str]:
    task = _task(task_id, user)
    if task.plan.refused:
        raise HTTPException(409, "Refused tasks cannot be approved")
    task.consented = req.approved
    task.status = "running" if req.approved else "declined"
    task.add_log("consent", approved=req.approved)
    store.put(task)
    return {"status": task.status}


@app.post("/v1/tasks/{task_id}/step", response_model=StepResponse)
async def step(task_id: str, req: StepRequest, user: str = Depends(current_user)) -> StepResponse:
    task = _task(task_id, user)
    if task.status != "running" or not task.consented:
        raise HTTPException(409, f"Task is {task.status}")
    if task.turns >= settings.max_turns:
        task.status = "failed"
        store.put(task)
        return StepResponse(done=True, message="")

    for r in req.results:
        task.add_log("result", name=r.name, call_id=r.call_id, result=r.result)
    res = await next_actions(task, req.screen, [r.model_dump() for r in req.results])
    for a in res.actions:
        task.add_log("action", name=a.name, intent=a.intent, gate=a.gate.value, reason=a.reason)
    if res.done:
        task.add_log("done", message=res.message)
    store.put(task)
    return res


@app.post("/v1/tasks/{task_id}/stop")
def stop(task_id: str, user: str = Depends(current_user)) -> dict[str, str]:
    task = _task(task_id, user)
    task.status = "stopped"
    task.add_log("stop")
    store.put(task)
    return {"status": "stopped"}


@app.get("/v1/tasks/{task_id}")
def get_task(task_id: str, user: str = Depends(current_user)) -> TaskRecord:
    return _task(task_id, user)


@app.post("/v1/screen/explain", response_model=ScreenExplanation)
async def explain(req: ExplainRequest, user: str = Depends(current_user)) -> ScreenExplanation:
    return await explain_screen(req.language, req.screen)


@app.post("/v1/scam/check", response_model=ScamCheckResponse)
async def scam_check(req: ScamCheckRequest, user: str = Depends(current_user)) -> ScamCheckResponse:
    res = await check_message(req)
    log.info("scam_check user=%s risk=%s reasons=%s", user, res.risk, res.reasons)
    return res


@app.post("/v1/medicine/read", response_model=MedicineInfo)
async def medicine_read(req: MedicineRequest, user: str = Depends(current_user)) -> MedicineInfo:
    # The photo is processed in memory only; it is never stored server-side.
    return await read_medicine(req)
