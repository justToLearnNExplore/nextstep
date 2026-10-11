"""Small single-purpose agents for the home-screen helpers: reading a grocery list photo and
summarising what the senior missed in the last hour."""

import base64
import datetime as dt

from google.adk.agents import LlmAgent
from google.genai import types
from pydantic import BaseModel, Field

from ..config import settings
from ..i18n import language
from .runtime import agent_config, run_structured


class GroceryItem(BaseModel):
    name: str = Field(description="Item as a shop would call it, in English, e.g. 'tomatoes', 'toor dal'.")
    quantity: str = Field(default="", description="As written, e.g. '1 kg', '2 packets'. Empty if not written.")


class GroceryList(BaseModel):
    readable: bool
    items: list[GroceryItem] = Field(default_factory=list)
    say_to_user: str = Field(description="In the user's language: the items read back simply, ending with a question asking whether to order them. If unreadable, how to retake the photo.")


GROCERY_INSTRUCTION = """
You read a photo of a grocery list written by an older adult in India (handwritten or printed,
any of English, Hindi or Kannada, maybe mixed). Extract each item with its quantity. Translate
item names to the English words a grocery app uses. Ignore crossed-out items. If the photo is not
a list or can't be read, set readable=false and explain kindly how to retake it.
"""

grocery_agent = LlmAgent(
    name="grocery_list_reader",
    model=settings.reasoning_model,
    description="Reads grocery list photos.",
    instruction=GROCERY_INSTRUCTION,
    output_schema=GroceryList,
    generate_content_config=agent_config(),
)


async def read_grocery_list(image_b64: str, lang_tag: str) -> GroceryList:
    image = types.Part.from_bytes(data=base64.b64decode(image_b64), mime_type="image/jpeg")
    text = f"User language: {language(lang_tag).name} ({lang_tag})"
    return await run_structured(grocery_agent, GroceryList, [types.Part(text=text), image], settings.reasoning_fallbacks)


class Missed(BaseModel):
    summary: str = Field(description="What the user missed, in their language, 2-5 short spoken sentences.")


MISSED_INSTRUCTION = """
Summarise for an older adult what they missed on their phone in the last hour: missed calls and
new messages. Group by person, most important first (family, doctor, repeated calls). Mention
names, not numbers, when a name is known. Keep it short and warm, to be read aloud. Never read
out OTPs, PINs or codes; say "a verification code" instead. Flag messages that look like scams.
If there is nothing, say so kindly.
"""

missed_agent = LlmAgent(
    name="missed_summariser",
    model=settings.fast_model,
    description="Summarises missed calls and messages.",
    instruction=MISSED_INSTRUCTION,
    output_schema=Missed,
    generate_content_config=agent_config(20000),
)


async def summarise_missed(lang_tag: str, calls: list[dict], messages: list[dict]) -> Missed:
    def fmt_time(ms: int) -> str:
        return dt.datetime.fromtimestamp(ms / 1000).strftime("%H:%M")

    lines = [f"Missed call from {c.get('name') or c.get('number', 'unknown')} at {fmt_time(int(c.get('at', 0)))}" for c in calls[:30]]
    lines += [f"{m.get('app', 'Message')} from {m.get('from', 'someone')} at {fmt_time(int(m.get('at', 0)))}: {str(m.get('text', ''))[:300]}" for m in messages[:40]]
    text = f"User language: {language(lang_tag).name} ({lang_tag})\nLast hour:\n" + ("\n".join(lines) or "(nothing)")
    return await run_structured(missed_agent, Missed, [types.Part(text=text)], settings.fast_fallbacks)
