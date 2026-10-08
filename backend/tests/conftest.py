import pytest

from app.protocol import Screen, TaskPlan, UiNode
from app.store import TaskRecord


def node(i, text=None, b=(0, 0, 100, 100), **kw):
    return UiNode(i=i, text=text, b=list(b), **kw)


@pytest.fixture
def blinkit_checkout():
    return Screen(
        package="com.grofers.customerapp",
        width=1000,
        height=2000,
        nodes=[
            node(0, "Amul Taaza Toned Milk 1 L", b=(0, 200, 1000, 400)),
            node(1, "Cash on Delivery", b=(0, 1600, 1000, 1700), click=True),
            node(2, "Place Order ₹68", b=(0, 1800, 1000, 1950), click=True),
            node(3, "Search", b=(0, 50, 1000, 150), click=True, edit=True),
        ],
    )


@pytest.fixture
def task():
    plan = TaskPlan(summary="I will order milk. Shall I?", steps=["Open Blinkit"], operator_goal="Order 1 L milk, COD")
    return TaskRecord(goal="order milk", language="en-IN", plan=plan, consented=True, status="running")
