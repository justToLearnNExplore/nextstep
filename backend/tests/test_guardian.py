from app.guardian import classify
from app.protocol import Gate, Screen

from .conftest import node


def test_search_tap_is_auto(blinkit_checkout):
    a = classify("click", {"x": 500, "y": 50, "intent": "Tap search"}, blinkit_checkout, "en-IN", True)
    assert a.gate == Gate.AUTO


def test_place_order_needs_confirmation_with_spoken_question(blinkit_checkout):
    a = classify("click", {"x": 500, "y": 935, "intent": "Place order: 1 L milk, ₹68, cash on delivery"},
                 blinkit_checkout, "en-IN", True)
    assert a.gate == Gate.CONFIRM
    assert "₹68" in a.ask and a.ask.endswith("Shall I go ahead?")


def test_hindi_confirmation_phrase(blinkit_checkout):
    a = classify("click", {"x": 500, "y": 935, "intent": "ऑर्डर"}, blinkit_checkout, "hi-IN", True)
    assert a.gate == Gate.CONFIRM and "आगे" in a.ask


def test_password_field_is_private():
    s = Screen(package="com.grofers.customerapp", width=1000, height=1000,
               nodes=[node(0, b=(0, 0, 1000, 200), edit=True, secure=True, focus=True)])
    assert classify("click", {"x": 500, "y": 100}, s, "en-IN", True).gate == Gate.PRIVATE
    assert classify("type", {"text": "hunter2"}, s, "en-IN", True).gate == Gate.PRIVATE


def test_otp_field_is_private_even_if_not_marked_secure():
    s = Screen(package="x", width=1000, height=1000,
               nodes=[node(0, "Enter OTP", b=(0, 0, 1000, 200), edit=True, focus=True)])
    assert classify("type", {"text": "123456"}, s, "en-IN", True).gate == Gate.PRIVATE


def test_payment_app_is_private():
    s = Screen(package="com.phonepe.app", width=1000, height=1000)
    assert classify("click", {"x": 1, "y": 1}, s, "en-IN", True).gate == Gate.PRIVATE
    assert classify("open_app", {"app_name": "PhonePe"}, Screen(width=1, height=1), "en-IN", True).gate == Gate.PRIVATE


def test_install_requires_confirmation():
    a = classify("open_play_store", {"package": "com.grofers.customerapp", "app_name": "Blinkit"},
                 Screen(width=1, height=1), "kn-IN", True)
    assert a.gate == Gate.CONFIRM and "Blinkit" in a.ask


def test_suspicious_link_in_whatsapp_is_blocked():
    s = Screen(package="com.whatsapp", width=1000, height=1000,
               nodes=[node(0, "Update KYC now sbi-kyc-update.in/verify", b=(0, 0, 1000, 200), click=True)])
    assert classify("click", {"x": 500, "y": 100}, s, "en-IN", True).gate == Gate.BLOCKED


def test_official_link_in_whatsapp_is_not_blocked():
    s = Screen(package="com.whatsapp", width=1000, height=1000,
               nodes=[node(0, "https://www.onlinesbi.sbi", b=(0, 0, 1000, 200), click=True)])
    assert classify("click", {"x": 500, "y": 100}, s, "en-IN", True).gate == Gate.AUTO


def test_model_safety_decision_can_only_raise(blinkit_checkout):
    a = classify("click", {"x": 500, "y": 50, "safety_decision": {"decision": "require_confirmation", "explanation": "checkout"}},
                 blinkit_checkout, "en-IN", True)
    assert a.gate == Gate.CONFIRM
    assert "safety_decision" not in a.args


def test_no_consent_blocks_everything(blinkit_checkout):
    assert classify("click", {"x": 500, "y": 50}, blinkit_checkout, "en-IN", False).gate == Gate.BLOCKED
