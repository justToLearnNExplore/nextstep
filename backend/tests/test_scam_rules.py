from app.agents.scam_shield import rule_signals
from app.official_sites import is_lookalike, is_official_domain


def test_lookalike_bank_domain_is_high():
    risk, reasons, brand = rule_signals("Dear customer your SBI account is blocked. Update KYC at sbi-kyc-update.in today")
    assert risk == "high"
    assert brand == "State Bank of India"
    assert any(r.startswith("lookalike_domain") for r in reasons)


def test_parcel_scam_with_link_is_high():
    risk, _, _ = rule_signals("India Post: your parcel is on hold. Pay Rs 25 redelivery fee immediately http://ind-post.top/pay")
    assert risk == "high"


def test_normal_family_message_is_none():
    risk, reasons, _ = rule_signals("Amma I will come home by 7, keep dinner ready")
    assert risk == "none" and reasons == []


def test_official_domains():
    assert is_official_domain("www.onlinesbi.sbi")
    assert is_official_domain("incometax.gov.in")
    assert not is_official_domain("incometax-refund.in")
    assert is_lookalike("hdfcbank-secure.co") is not None
    assert is_lookalike("hdfcbank.com") is None
