package com.northstar.nextstep.agent.voice

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

/**
 * Speaks and listens in the user's language from anywhere (the overlay runs on top of other apps,
 * so this cannot depend on the React Native activity being in the foreground).
 * Language is a BCP-47 tag such as "hi-IN"; adding a language needs no code change here.
 */
class VoiceIO(private val context: Context) {
  private val main = Handler(Looper.getMainLooper())
  private var ttsReady = false
  private val tts = TextToSpeech(context.applicationContext) { ttsReady = it == TextToSpeech.SUCCESS }
  private var recognizer: SpeechRecognizer? = null

  /** Slightly slower than default: easier to follow for older listeners. */
  var speechRate = 0.9f

  fun speak(text: String, lang: String, onDone: (() -> Unit)? = null) {
    if (!ttsReady || text.isBlank()) { onDone?.invoke(); return }
    tts.language = Locale.forLanguageTag(lang)
    tts.setSpeechRate(speechRate)
    val id = UUID.randomUUID().toString()
    tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
      override fun onStart(utteranceId: String?) {}
      override fun onDone(utteranceId: String?) { if (utteranceId == id) main.post { onDone?.invoke() } }
      @Deprecated("Deprecated in Java")
      override fun onError(utteranceId: String?) { if (utteranceId == id) main.post { onDone?.invoke() } }
    })
    tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, id)
  }

  /** Blocking variant for the agent worker thread. */
  fun speakAndWait(text: String, lang: String, timeoutMs: Long = 30_000) {
    val f = CompletableFuture<Unit>()
    main.post { speak(text, lang) { f.complete(Unit) } }
    runCatching { f.get(timeoutMs, TimeUnit.MILLISECONDS) }
  }

  fun stopSpeaking() = tts.stop()

  fun listen(lang: String, onPartial: (String) -> Unit = {}, onResult: (String?) -> Unit) = main.post {
    if (!SpeechRecognizer.isRecognitionAvailable(context)) { onResult(null); return@post }
    recognizer?.destroy()
    recognizer = SpeechRecognizer.createSpeechRecognizer(context).apply {
      setRecognitionListener(object : RecognitionListener {
        override fun onResults(b: Bundle?) {
          onResult(b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull())
        }
        override fun onPartialResults(b: Bundle?) {
          b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.let(onPartial)
        }
        override fun onError(error: Int) = onResult(null)
        override fun onReadyForSpeech(p: Bundle?) {}
        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(v: Float) {}
        override fun onBufferReceived(b: ByteArray?) {}
        override fun onEndOfSpeech() {}
        override fun onEvent(t: Int, p: Bundle?) {}
      })
      startListening(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        putExtra(RecognizerIntent.EXTRA_LANGUAGE, lang)
        putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        // Older users pause mid-sentence; give them time.
        putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 2500L)
      })
    }
  }

  fun listenAndWait(lang: String, timeoutMs: Long = 15_000): String? {
    val f = CompletableFuture<String?>()
    listen(lang) { f.complete(it) }
    return runCatching { f.get(timeoutMs, TimeUnit.MILLISECONDS) }.getOrNull()
  }

  fun cancelListening() = main.post { recognizer?.cancel() }

  fun shutdown() {
    main.post { recognizer?.destroy(); recognizer = null }
    tts.shutdown()
  }
}
