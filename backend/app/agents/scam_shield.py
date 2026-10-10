"""Scam shield: deterministic signals + a fast Gemini classifier, combined conservatively."""

import logging
import re
from urllib.parse import urlparse

from google.adk.agents import LlmAgent
from google.genai import types

from ..config import settings
from ..i18n import language, phrase
from ..official_sites import CYBER_CRIME, find_by_alias, find_by_name, is_lookalike, is_official_domain
from ..protocol import ScamCheckRequest, ScamCheckResponse, ScamVerdict
from .runtime import run_structured

INSTRUCTION = """
You check one SMS/WhatsApp message received by an older adult in India for fraud.
Common scams: fake KYC/PAN/Aadhaar update, account blocked, electricity disconnection tonight,
parcel held by India Post/courier, income-tax refund, lottery/prize, job offers, "Hi Mum/Dad new
number", requests for OTP/PIN/UPI collect requests, links to install AnyDesk/TeamViewer/APK files,
fake police/CBI "digital arrest" threats.

Return risk none|low|medium|high with short English reason codes and `impersonates` if the message
pretends to be an organisation. Write `warning` in the user's language, 1-2 calm sentences. When
risk is medium or high it must say: do not open the link and do not share any details/OTP.
For none/low, `warning` can be empty. Never repeat the link in the warning.
"""

scam_agent = LlmAgent(
    name="scam_shield",
    model=settings.fast_model,
    description="Classifies incoming messages for scam risk.",
    instruction=INSTRUCTION,
    output_schema=ScamVerdict,
    generate_content_config=types.GenerateContentConfig(temperature=0.0),
)

URL = re.compile(r"(https?://[^\s]+|\b[\w-]+(?:\.[\w-]+)*\.(?:com|in|net|org|co|xyz|top|info|link|ly|me|site|online|app)\b[^\s]*)", re.I)
URGENCY = re.compile(r"(urgent|immediately|today|tonight|within 24|last chance|blocked|suspend|disconnect|expire|arrest|legal action|तुरंत|आज ही|बंद|ತಕ್ಷಣ|ಇಂದೇ)", re.I)
ASKS_SECRET = re.compile(r"(otp|\bpin\b|cvv|password|upi|kyc|pan card|aadhaar|anydesk|teamviewer|\.apk|ओटीपी|पिन|ಒಟಿಪಿ|ಪಿನ್)", re.I)
MONEY = re.compile(r"(₹|rs\.?|inr|refund|prize|lottery|cashback|reward|won|jeeta|ಬಹುಮಾನ|इनाम)", re.I)

_RANK = {"none": 0, "low": 1, "medium": 2, "high": 3}


def rule_signals(text: str) -> tuple[str, list[str], str | None]:
    reasons: list[str] = []
    brand = None
    for raw in URL.findall(text):
        url = raw if raw.lower().startswith("http") else f"https://{raw}"
        host = (urlparse(url).hostname or "").lower()
        if not host:
            continue
        if (o := is_lookalike(host)) is not None:
            reasons.append(f"lookalike_domain:{host}")
            brand = o.name
        elif not is_official_domain(host):
            reasons.append(f"unverified_link:{host}")
    if URGENCY.search(text):
        reasons.append("urgency")
    if ASKS_SECRET.search(text):
        reasons.append("asks_sensitive_info")
    if MONEY.search(text):
        reasons.append("money_bait")

    has_link = any(r.startswith(("lookalike", "unverified")) for r in reasons)
    if any(r.startswith("lookalike") for r in reasons) or (has_link and "asks_sensitive_info" in reasons):
        risk = "high"
    elif has_link and ("urgency" in reasons or "money_bait" in reasons):
        risk = "high"
    elif "asks_sensitive_info" in reasons and "urgency" in reasons:
        risk = "medium"
    elif has_link or len(reasons) >= 2:
        risk = "low"
    else:
        risk = "none"
    return risk, reasons, brand


async def check_message(req: ScamCheckRequest) -> ScamCheckResponse:
    rule_risk, rule_reasons, rule_brand = rule_signals(req.text)
    try:
        verdict = await run_structured(
            scam_agent,
            ScamVerdict,
            [types.Part(text=f"User language: {language(req.language).name} ({req.language})\n"
                             f"Sender: {req.sender}\nApp: {req.source}\nMessage:\n{req.text}")],
        )
    except Exception:  # model unavailable: rules alone still protect the user
        logging.getLogger("nextstep").exception("scam_shield model call failed; using rules only")
        verdict = ScamVerdict(risk="none", warning="")

    # Conservative merge: the higher risk wins.
    risk = rule_risk if _RANK[rule_risk] > _RANK[verdict.risk] else verdict.risk
    official = find_by_name(verdict.impersonates) or (find_by_name(rule_brand) if rule_brand else None) or find_by_alias(req.text)
    if risk in ("medium", "high") and official is None:
        official = CYBER_CRIME
    return ScamCheckResponse(
        risk=risk,
        reasons=sorted(set(rule_reasons + verdict.reasons)),
        impersonates=verdict.impersonates or rule_brand,
        warning=verdict.warning or (phrase(req.language, "scam_warning") if risk in ("medium", "high") else ""),
        # Only ever a verified URL from our registry, never one from the message.
        official_url=official.url if official and risk in ("medium", "high") else None,
        official_label=official.name if official and risk in ("medium", "high") else None,
    )
