"""Speech-to-text with Google Cloud Speech-to-Text.

Seniors' phones often lack offline speech packs for Hindi/Kannada/Indian English, so the phone
records a short clip and we transcribe it here. Billed to the Cloud project (not Gemini quota).
"""

import base64

from google.cloud import speech_v1 as speech

from .i18n import language

_client: speech.SpeechAsyncClient | None = None

# App and task words seniors say often; boosts recognition of brand names.
_HINTS = ["Blinkit", "WhatsApp", "YouTube", "NextStep", "milk", "medicine", "bhajan", "doctor", "order"]


def client() -> speech.SpeechAsyncClient:
    global _client
    if _client is None:
        _client = speech.SpeechAsyncClient()
    return _client


def config(lang_tag: str, sample_rate: int) -> speech.RecognitionConfig:
    cfg = speech.RecognitionConfig(
        encoding=speech.RecognitionConfig.AudioEncoding.LINEAR16,
        sample_rate_hertz=sample_rate,
        language_code=language(lang_tag).tag,
        enable_automatic_punctuation=True,
        speech_contexts=[speech.SpeechContext(phrases=_HINTS)],
    )
    # latest_long is markedly better for Indian English; the default model is best for hi/kn.
    if cfg.language_code.startswith("en"):
        cfg.model = "latest_long"
    return cfg


async def transcribe(audio_b64: str, lang_tag: str, sample_rate: int = 16000) -> str:
    audio = speech.RecognitionAudio(content=base64.b64decode(audio_b64))
    res = await client().recognize(config=config(lang_tag, sample_rate), audio=audio, timeout=20)
    return " ".join(r.alternatives[0].transcript for r in res.results if r.alternatives).strip()
