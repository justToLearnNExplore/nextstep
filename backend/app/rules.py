"""Free, deterministic handling of common interruptions, so they never cost a model call.

Only dismissive, harmless choices are made here ("Not now", "Skip", "Close"). Anything that
grants, buys, sends or signs in is left to the AI + Guardian.
"""

import re
from typing import Any

from .protocol import Screen

DISMISS = re.compile(
    r"^(not now|no thanks|no, thanks|skip|maybe later|later|remind me later|close|dismiss|cancel|"
    r"अभी नहीं|बाद में|छोड़ें|ಈಗ ಬೇಡ|ನಂತರ|ಬಿಟ್ಟುಬಿಡಿ)$",
    re.I,
)
# Words that show the dialog is an interruption (not part of the task).
INTERRUPTION = re.compile(
    r"(rate (us|this app)|enjoying|update available|new version|turn on notifications|allow notifications|"
    r"subscribe to|sign up for offers|install our app|special offer|ad\b|advertisement|whats new|what's new)",
    re.I,
)


def interruption_action(screen: Screen) -> dict[str, Any] | None:
    """A 'dismiss' tap if the screen shows a known interruption, else None."""
    labels = [n.label() for n in screen.nodes if not n.secure]
    if not any(INTERRUPTION.search(lbl) for lbl in labels):
        return None
    for n in screen.nodes:
        if n.click and DISMISS.match(n.label().strip()):
            x, y = screen.norm_box(n)
            return {"x": x, "y": y, "intent": f"Close the pop-up ('{n.label()}')"}
    return None


def needs_screenshot(screen: Screen, last_failed: bool, first_step: bool) -> bool:
    """Text-first policy: send the image only when the text alone is likely not enough."""
    if first_step or last_failed or not screen.nodes:
        return True
    readable = [n for n in screen.nodes if n.label() and not n.secure]
    tappable_unlabelled = [n for n in screen.nodes if n.click and not n.label()]
    return len(readable) < 6 or screen.text_chars() < 120 or len(tappable_unlabelled) > len(readable)
