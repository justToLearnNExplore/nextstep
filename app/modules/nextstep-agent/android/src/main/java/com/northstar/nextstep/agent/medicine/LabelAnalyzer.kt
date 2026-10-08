package com.northstar.nextstep.agent.medicine

import android.os.SystemClock
import androidx.annotation.OptIn
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlin.math.abs

/** What the camera sees, reduced to the few numbers that decide the spoken guidance. */
data class FrameMetrics(
  val brightness: Double, // mean luma 0-255
  val sharpness: Double, // Laplacian variance on a downsampled frame
  val motion: Double, // mean abs luma change vs previous frame
  val textChars: Int,
  val lineHeight: Double, // median text line height as a fraction of frame height
  val touchesEdge: Boolean, // recognised text is cut off at the frame border
)

/**
 * Guidance states, highest priority first. The JS layer maps each to a sentence in the user's
 * language and speaks it. Thresholds are deliberately forgiving: shaky hands, dim rooms.
 */
object Guidance {
  const val MORE_LIGHT = "more_light"
  const val HOLD_STEADY = "hold_steady"
  const val FIND_LABEL = "find_label"
  const val MOVE_CLOSER = "move_closer"
  const val MOVE_BACK = "move_back"
  const val READY = "ready"

  var minBrightness = 55.0
  var maxMotion = 14.0
  var minSharpness = 35.0
  var minChars = 6
  var minLineHeight = 0.028
  var maxLineHeight = 0.16

  fun decide(m: FrameMetrics): String = when {
    m.brightness < minBrightness -> MORE_LIGHT
    m.motion > maxMotion -> HOLD_STEADY
    m.textChars < minChars -> FIND_LABEL
    m.lineHeight < minLineHeight -> MOVE_CLOSER
    m.lineHeight > maxLineHeight || (m.touchesEdge && m.lineHeight > 0.07) -> MOVE_BACK
    m.sharpness < minSharpness -> HOLD_STEADY
    else -> READY
  }
}

/**
 * CameraX analyzer: cheap luma statistics on every frame, ML Kit OCR a few times a second.
 * Reports a guidance state when it changes (or periodically), only after it is stable for two
 * evaluations, so the voice prompt does not flicker.
 */
class LabelAnalyzer(
  private val onGuidance: (state: String, metrics: FrameMetrics, preview: String) -> Unit,
) : ImageAnalysis.Analyzer {

  private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
  private var prevSample: IntArray? = null
  private var lastOcrAt = 0L
  private var lastText: Text? = null
  private var lastFrameH = 1
  private var lastFrameW = 1
  private var candidate: String? = null
  private var reported: String? = null
  private var lastReportAt = 0L

  @OptIn(ExperimentalGetImage::class)
  override fun analyze(proxy: ImageProxy) {
    val (brightness, sharpness, motion) = lumaStats(proxy)
    val now = SystemClock.elapsedRealtime()
    val media = proxy.image
    if (media != null && now - lastOcrAt > OCR_INTERVAL_MS) {
      lastOcrAt = now
      val rot = proxy.imageInfo.rotationDegrees
      // ML Kit returns boxes in upright coordinates.
      lastFrameW = if (rot % 180 == 0) proxy.width else proxy.height
      lastFrameH = if (rot % 180 == 0) proxy.height else proxy.width
      recognizer.process(InputImage.fromMediaImage(media, rot))
        .addOnSuccessListener { lastText = it }
        .addOnCompleteListener {
          evaluate(brightness, sharpness, motion)
          proxy.close()
        }
    } else {
      evaluate(brightness, sharpness, motion)
      proxy.close()
    }
  }

  private fun evaluate(brightness: Double, sharpness: Double, motion: Double) {
    val text = lastText
    val lines = text?.textBlocks?.flatMap { it.lines }.orEmpty()
    val heights = lines.mapNotNull { it.boundingBox?.height() }.sorted()
    val median = if (heights.isEmpty()) 0.0 else heights[heights.size / 2].toDouble() / lastFrameH
    val margin = (lastFrameW * 0.02).toInt()
    val edge = lines.any { l ->
      l.boundingBox?.let { it.left <= margin || it.right >= lastFrameW - margin } ?: false
    }
    val chars = text?.text?.count { it.isLetterOrDigit() } ?: 0
    val m = FrameMetrics(brightness, sharpness, motion, chars, median, edge)
    val state = Guidance.decide(m)

    val now = SystemClock.elapsedRealtime()
    if (state == candidate && (state != reported || now - lastReportAt > REPEAT_MS)) {
      reported = state
      lastReportAt = now
      val preview = lines.sortedByDescending { it.boundingBox?.height() ?: 0 }.take(3).joinToString(" · ") { it.text }
      onGuidance(state, m, preview.take(80))
    }
    candidate = state
  }

  /** Mean luma, sharpness (Laplacian variance) and motion on an 80x60 sample of the Y plane. */
  private fun lumaStats(proxy: ImageProxy): Triple<Double, Double, Double> {
    val plane = proxy.planes[0]
    val buf = plane.buffer
    val rowStride = plane.rowStride
    val pixStride = plane.pixelStride
    val w = SAMPLE_W; val h = SAMPLE_H
    val sample = IntArray(w * h)
    var sum = 0L
    for (y in 0 until h) {
      val row = (y * proxy.height / h) * rowStride
      for (x in 0 until w) {
        val v = buf.get(row + (x * proxy.width / w) * pixStride).toInt() and 0xFF
        sample[y * w + x] = v
        sum += v
      }
    }
    val mean = sum.toDouble() / sample.size

    var lapSum = 0.0; var lapSq = 0.0; var n = 0
    for (y in 1 until h - 1) for (x in 1 until w - 1) {
      val i = y * w + x
      val lap = (4 * sample[i] - sample[i - 1] - sample[i + 1] - sample[i - w] - sample[i + w]).toDouble()
      lapSum += lap; lapSq += lap * lap; n++
    }
    val lapMean = lapSum / n
    val sharpness = lapSq / n - lapMean * lapMean

    val prev = prevSample
    val motion = if (prev == null) 0.0 else sample.indices.sumOf { abs(sample[it] - prev[it]) }.toDouble() / sample.size
    prevSample = sample
    return Triple(mean, sharpness, motion)
  }

  fun close() = recognizer.close()

  companion object {
    const val SAMPLE_W = 80
    const val SAMPLE_H = 60
    const val OCR_INTERVAL_MS = 450L
    const val REPEAT_MS = 6000L
  }
}
