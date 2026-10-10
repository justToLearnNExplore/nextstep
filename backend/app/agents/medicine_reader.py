"""Medicine reader: identifies a medicine from a label photo (plus on-device OCR text) and drafts
the WhatsApp caption. Identification only: never dosage or treatment advice."""

import base64
import datetime as dt

from google.adk.agents import LlmAgent
from google.genai import types

from ..config import settings
from ..i18n import language
from ..protocol import MedicineInfo, MedicineRequest
from .runtime import agent_config, run_structured

INSTRUCTION = """
You read medicine packaging photographed by an older adult in India (strip, bottle, box or
prescription). Use the photo first; the OCR text from the phone may contain errors.

- Extract brand name, active ingredient(s) with strength, form and printed expiry.
- If the label is blurred, cut off or not a medicine, set readable=false and say kindly in
  `say_to_user` how to retake it (closer, more light, show the name side).
- `expired` is true only if the printed expiry is clearly before today's date (given below).
- Never give advice about dosage, use, side effects or substitutes. Never diagnose.
- `say_to_user`: in the user's language, 1-2 short sentences, e.g. "This looks like Dolo 650,
  paracetamol tablets. It expires in August 2027."
- `caption`: in the user's language, first person, polite, for the recipient (doctor or family
  member), e.g. "Doctor, this is the medicine I have: Dolo 650 (Paracetamol 650 mg).
  Please check if this is the right one." Mention expiry if expired. Keep it under 40 words.
"""

medicine_agent = LlmAgent(
    name="medicine_reader",
    model=settings.reasoning_model,
    description="Reads medicine labels from photos and drafts a message to the doctor or family.",
    instruction=INSTRUCTION,
    output_schema=MedicineInfo,
    generate_content_config=agent_config(),
)


async def read_medicine(req: MedicineRequest) -> MedicineInfo:
    text = (
        f"Today: {dt.date.today().isoformat()}\n"
        f"User language: {language(req.language).name} ({req.language})\n"
        f"Recipient: {req.recipient}\n"
        f"OCR text from phone:\n{req.ocr_text[:2000] or '(none)'}"
    )
    image = types.Part.from_bytes(data=base64.b64decode(req.image_b64), mime_type="image/jpeg")
    return await run_structured(medicine_agent, MedicineInfo, [types.Part(text=text), image], settings.reasoning_fallbacks)
