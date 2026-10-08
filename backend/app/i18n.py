"""Backend language registry.

The models produce user-facing text directly in the user's language; this module only names
the language for prompts and holds the few fixed phrases the Guardian adds itself.
To add a language: add one entry to LANGUAGES. Unknown tags still work (model is told the
BCP-47 tag) and fall back to English phrases.
"""

from dataclasses import dataclass, field


@dataclass(frozen=True)
class Language:
    tag: str
    name: str  # English name, used inside prompts
    phrases: dict[str, str] = field(default_factory=dict)


_EN = {
    "confirm_suffix": "Shall I go ahead?",
    "yes_go": "Yes, go ahead",
    "install": "I need to install {app}. I will open the Play Store; please tap Install yourself.",
    "private": "This step is private. Please do it yourself, then tap Continue.",
    "payment_app": "This is a payment app. Please complete it yourself, then tap Continue.",
    "blocked_link": "I won't open this link. It may not be safe.",
    "no_consent": "I need your approval before I start.",
    "scam_warning": "This message looks like a scam. Do not open the link and do not share any details or OTP.",
}

LANGUAGES: dict[str, Language] = {
    "en-IN": Language("en-IN", "English (simple Indian English)", _EN),
    "hi-IN": Language(
        "hi-IN",
        "Hindi (simple, everyday Hindi in Devanagari script)",
        {
            "confirm_suffix": "क्या मैं आगे बढ़ूँ?",
            "yes_go": "हाँ, आगे बढ़ो",
            "install": "मुझे {app} इंस्टॉल करना होगा। मैं Play Store खोलता हूँ, Install आप ख़ुद दबाइए।",
            "private": "यह कदम निजी है। कृपया इसे ख़ुद करें, फिर आगे बढ़ें दबाएँ।",
            "payment_app": "यह पेमेंट ऐप है। कृपया इसे ख़ुद पूरा करें, फिर आगे बढ़ें दबाएँ।",
            "blocked_link": "मैं यह लिंक नहीं खोलूँगा। यह सुरक्षित नहीं हो सकता।",
            "no_consent": "शुरू करने से पहले मुझे आपकी अनुमति चाहिए।",
            "scam_warning": "यह मैसेज धोखा लगता है। लिंक मत खोलिए और कोई जानकारी या ओटीपी मत बताइए।",
        },
    ),
    "kn-IN": Language(
        "kn-IN",
        "Kannada (simple, everyday Kannada in Kannada script)",
        {
            "confirm_suffix": "ಮುಂದುವರಿಯಲೇ?",
            "yes_go": "ಹೌದು, ಮುಂದುವರಿಸಿ",
            "install": "{app} ಇನ್‌ಸ್ಟಾಲ್ ಮಾಡಬೇಕು. ನಾನು Play Store ತೆರೆಯುತ್ತೇನೆ, Install ಅನ್ನು ನೀವೇ ಒತ್ತಿ.",
            "private": "ಈ ಹಂತ ಖಾಸಗಿ. ದಯವಿಟ್ಟು ನೀವೇ ಮಾಡಿ, ನಂತರ ಮುಂದುವರಿಸಿ ಒತ್ತಿ.",
            "payment_app": "ಇದು ಪಾವತಿ ಆ್ಯಪ್. ದಯವಿಟ್ಟು ನೀವೇ ಪೂರ್ಣಗೊಳಿಸಿ, ನಂತರ ಮುಂದುವರಿಸಿ ಒತ್ತಿ.",
            "blocked_link": "ಈ ಲಿಂಕ್ ತೆರೆಯುವುದಿಲ್ಲ. ಇದು ಸುರಕ್ಷಿತವಲ್ಲದಿರಬಹುದು.",
            "no_consent": "ಶುರು ಮಾಡುವ ಮೊದಲು ನಿಮ್ಮ ಒಪ್ಪಿಗೆ ಬೇಕು.",
            "scam_warning": "ಈ ಸಂದೇಶ ಮೋಸದಂತೆ ಕಾಣುತ್ತದೆ. ಲಿಂಕ್ ತೆರೆಯಬೇಡಿ, ಯಾವುದೇ ವಿವರ ಅಥವಾ ಒಟಿಪಿ ಹಂಚಿಕೊಳ್ಳಬೇಡಿ.",
        },
    ),
}


def language(tag: str) -> Language:
    if tag in LANGUAGES:
        return LANGUAGES[tag]
    base = tag.split("-")[0]
    for lang in LANGUAGES.values():
        if lang.tag.split("-")[0] == base:
            return lang
    return Language(tag, f"the language with BCP-47 tag '{tag}'", _EN)


def phrase(tag: str, key: str, **kw: str) -> str:
    text = language(tag).phrases.get(key) or _EN[key]
    return text.format(**kw) if kw else text
