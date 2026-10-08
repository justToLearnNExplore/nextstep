"""Screen explainer: "What is this screen?" in plain words, with the safest next step."""

from google.adk.agents import LlmAgent
from google.genai import types

from ..config import settings
from ..i18n import language
from ..protocol import Screen, ScreenExplanation
from .runtime import image_part, run_structured

INSTRUCTION = """
You help an older adult who is confused by their phone screen.
Look at the screenshot and the accessibility tree and explain, in the user's language:
- what this screen is (app and purpose) in 2-3 short, calm sentences;
- what is safe to do next.

Risk:
- "danger" if the screen asks for a UPI PIN, OTP, password, card details, remote-access app
  install, or looks like a scam/phishing page or a fake prize/KYC page. Say clearly:
  never share PIN/OTP/passwords. Recommend "back".
- "caution" for payment pages, permission prompts, ads with install buttons, unknown links.
- "none" otherwise.

Options: choose 2-3 of ids back, home, close, continue. Labels are short verbs in the user's
language (e.g. "Take me back", "Go home"). Mark exactly one as recommended (the safest).
Never tell the user to type sensitive information.
"""

explainer_agent = LlmAgent(
    name="screen_explainer",
    model=settings.reasoning_model,
    description="Explains the current phone screen simply and suggests the safest action.",
    instruction=INSTRUCTION,
    output_schema=ScreenExplanation,
    generate_content_config=types.GenerateContentConfig(temperature=0.2),
)


async def explain_screen(lang_tag: str, screen: Screen) -> ScreenExplanation:
    text = f"User language: {language(lang_tag).name} ({lang_tag})\nAccessibility tree:\n{screen.summary()}"
    return await run_structured(
        explainer_agent, ScreenExplanation, [types.Part(text=text), *image_part(screen.screenshot_b64)]
    )
