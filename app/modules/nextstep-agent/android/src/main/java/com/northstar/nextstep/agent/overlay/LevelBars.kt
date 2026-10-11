package com.northstar.nextstep.agent.overlay

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.View

/** A row of bars that follow the user's voice while NextStep listens (newest on the right). */
class LevelBars(context: Context) : View(context) {
  private val density = resources.displayMetrics.density
  private val levels = FloatArray(BARS)
  private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Palette.GO }
  private val bar = RectF()

  /** Adds the latest level (0..1). Main thread. */
  fun push(level: Float) {
    System.arraycopy(levels, 1, levels, 0, BARS - 1)
    // Speech is quiet on many phones: lift small levels so the bars visibly react.
    levels[BARS - 1] = Math.sqrt(level.toDouble()).toFloat().coerceIn(0f, 1f)
    invalidate()
  }

  /** Heard: flatten the bars and turn them grey while we transcribe. */
  fun settle() {
    levels.fill(0f)
    paint.color = Palette.MIST
    invalidate()
  }

  override fun onDraw(canvas: Canvas) {
    val w = 8f * density
    val gap = (width - BARS * w) / (BARS - 1)
    val min = 6f * density
    for (i in 0 until BARS) {
      val h = min + (height - min) * levels[i]
      val left = i * (w + gap)
      bar.set(left, (height - h) / 2, left + w, (height + h) / 2)
      canvas.drawRoundRect(bar, w / 2, w / 2, paint)
    }
  }

  private companion object { const val BARS = 16 }
}
