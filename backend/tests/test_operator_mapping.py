from types import SimpleNamespace as NS

from app.operator import to_step_response
from app.protocol import Gate


def call(name, call_id="c1", **args):
    return NS(type="function_call", name=name, id=call_id, arguments=args)


def test_actions_are_gated(task, blinkit_checkout):
    steps = [call("click", x=500, y=50, intent="Search for milk"), call("click", "c2", x=500, y=935, intent="Place order ₹68")]
    res = to_step_response(task, blinkit_checkout, steps)
    assert [a.gate for a in res.actions] == [Gate.AUTO, Gate.CONFIRM]
    assert res.actions[1].call_id == "c2"
    assert res.status_text == "Search for milk"


def test_task_complete_ends_task(task, blinkit_checkout):
    res = to_step_response(task, blinkit_checkout, [call("task_complete", success=True, message="Order placed")])
    assert res.done and res.message == "Order placed" and task.status == "done"


def test_text_only_output_ends_task(task, blinkit_checkout):
    out = NS(type="model_output", content=[NS(type="text", text="Done.")])
    res = to_step_response(task, blinkit_checkout, [out])
    assert res.done and res.message == "Done."


def test_hand_over_is_private(task, blinkit_checkout):
    res = to_step_response(task, blinkit_checkout, [call("hand_over_to_user", reason="Please enter the OTP")])
    assert res.actions[0].gate == Gate.PRIVATE and res.actions[0].ask == "Please enter the OTP"
