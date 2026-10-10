package com.northstar.nextstep.agent.voice

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Base64
import android.util.Log
import androidx.core.content.ContextCompat
import com.northstar.nextstep.agent.Prefs
import com.northstar.nextstep.agent.agent.BackendClient
import com.northstar.nextstep.agent.agent.BackendError
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.Locale
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.math.sqrt

/**
 * What the microphone produced. [error] is null on success, otherwise one of:
 * "silence" (nobody spoke), "mic_silent" (microphone delivers no sound at all, e.g. muted or an
 * emulator without host audio), "mic_unavailable", "permission", "network", "busy", "cancelled".
 */
data class Heard(val text: String?, val error: String?) {
  fun toMap() = mapOf("text" to text, "error" to error)
}

/**
 * Speaks (Android TextToSpeech) and listens in the user's language from anywhere, including the
 * overlay on top of other apps.
 *
 * Listening records a short clip and transcribes it with Google Cloud Speech-to-Text via the
 * backend. On-device recognizers on many seniors' phones lack Hindi/Kannada/Indian-English packs,
 * so cloud transcription is far more reliable. Language is a BCP-47 tag; new languages need no
 * code change here.
 */
class VoiceIO(private val context: Context) {
  private val main = Handler(Looper.getMainLooper())
  private val prefs = Prefs(context)
  private val api = BackendClient(prefs)
  private val recorder = Executors.newSingleThreadExecutor()
  @Volatile private var cancelled = false

  private var ttsReady = false
  private val tts = TextToSpeech(context.applicationContext) { ttsReady = it == TextToSpeech.SUCCESS }

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

  /** Records until the speaker pauses, transcribes, and calls back on the main thread. */
  fun listen(lang: String, onResult: (Heard) -> Unit) {
    cancelled = false
    recorder.execute {
      val heard = runCatching { recordAndTranscribe(lang) }
        .getOrElse { Log.e(TAG, "listen failed", it); Heard(null, "network") }
      Log.i(TAG, "listen($lang) -> error=${heard.error} chars=${heard.text?.length ?: 0}")
      main.post { onResult(heard) }
    }
  }

  fun listenAndWait(lang: String, timeoutMs: Long = 30_000): Heard {
    val f = CompletableFuture<Heard>()
    listen(lang) { f.complete(it) }
    return runCatching { f.get(timeoutMs, TimeUnit.MILLISECONDS) }.getOrDefault(Heard(null, "silence"))
  }

  fun cancelListening() { cancelled = true }

  private fun recordAndTranscribe(lang: String): Heard {
    if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
      return Heard(null, "permission")
    }
    val pcm = record() ?: return Heard(null, "mic_unavailable")
    if (cancelled) return Heard(null, "cancelled")
    if (pcm.peak < MIC_DEAD_PEAK) return Heard(null, "mic_silent")
    if (!pcm.speech) return Heard(null, "silence")

    return try {
      val res = api.post("/v1/stt", JSONObject()
        .put("language", lang)
        .put("sample_rate", RATE)
        .put("audio_b64", Base64.encodeToString(pcm.bytes, Base64.NO_WRAP)), timeoutMs = 30_000)
      val text = res.optString("text").trim()
      if (text.isEmpty()) Heard(null, "silence") else Heard(text, null)
    } catch (e: BackendError) {
      Log.w(TAG, "stt backend error ${e.code}", e)
      Heard(null, if (e.code == 503) "busy" else "network")
    } catch (e: IOException) {
      Log.w(TAG, "stt network error", e)
      Heard(null, "network")
    }
  }

  private class Pcm(val bytes: ByteArray, val peak: Double, val speech: Boolean)

  /**
   * Simple voice-activity detection: wait up to [WAIT_FOR_SPEECH_MS] for speech, then stop after
   * [END_SILENCE_MS] of quiet (older users pause; this is deliberately generous) or [MAX_MS].
   */
  private fun record(): Pcm? {
    val minBuf = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
    val rec = try {
      AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, RATE, AudioFormat.CHANNEL_IN_MONO,
        AudioFormat.ENCODING_PCM_16BIT, maxOf(minBuf, RATE / 2))
    } catch (e: SecurityException) { return null }
    if (rec.state != AudioRecord.STATE_INITIALIZED) { rec.release(); return null }

    val frame = ShortArray(RATE / 10) // 100 ms
    val out = ByteArrayOutputStream()
    var elapsed = 0; var quiet = 0; var speech = false; var peak = 0.0; var floor = -1.0
    try {
      rec.startRecording()
      while (!cancelled && elapsed < MAX_MS) {
        val n = rec.read(frame, 0, frame.size)
        if (n <= 0) break
        var sum = 0.0
        for (i in 0 until n) {
          val s = frame[i].toInt()
          sum += (s * s).toDouble()
          out.write(s and 0xFF); out.write((s shr 8) and 0xFF)
        }
        val rms = sqrt(sum / n)
        peak = maxOf(peak, rms)
        elapsed += 100
        // The first 300 ms set the room's noise floor.
        if (elapsed <= 300) { floor = if (floor < 0) rms else (floor + rms) / 2; continue }
        val threshold = maxOf(floor * 2.5, SPEECH_MIN_RMS)
        if (rms > threshold) { speech = true; quiet = 0 } else if (speech) quiet += 100
        if (speech && quiet >= END_SILENCE_MS) break
        if (!speech && elapsed >= WAIT_FOR_SPEECH_MS) break
      }
    } finally {
      runCatching { rec.stop() }
      rec.release()
    }
    Log.d(TAG, "recorded ${elapsed}ms peak=${peak.toInt()} floor=${floor.toInt()} speech=$speech")
    return Pcm(out.toByteArray(), peak, speech)
  }

  fun shutdown() {
    cancelled = true
    recorder.shutdownNow()
    tts.shutdown()
  }

  companion object {
    private const val TAG = "NextStepVoice"
    const val RATE = 16_000
    private const val MAX_MS = 12_000
    private const val WAIT_FOR_SPEECH_MS = 7_000
    private const val END_SILENCE_MS = 1_400
    private const val SPEECH_MIN_RMS = 400.0
    private const val MIC_DEAD_PEAK = 30.0
  }
}
