package com.northstar.nextstep.agent.overlay

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.provider.Settings
import android.view.View
import android.view.animation.LinearInterpolator

/** What the bubble shows about NextStep, at a glance. */
enum class BubbleState { IDLE, WORKING, LISTENING, WAITING }

/**
 * The ring drawn around the bubble logo. Calm by design (older users, long tasks):
 *  WORKING   – a pale full ring around the logo with a green arc turning slowly (one turn every 2 s)
 *  LISTENING – a soft saffron ring gently breathing
 *  WAITING   – a steady saffron ring and a small green "?" badge: NextStep needs the user
 *  IDLE      – nothing
 * Respects the system "remove animations" setting.
 */
class StatusRing(context: Context) : View(context) {
  private val density = resources.displayMetrics.density
  private val stroke = 4f * density
  private val arcPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
    style = Paint.Style.STROKE; strokeWidth = stroke; strokeCap = Paint.Cap.ROUND
  }
  private val badgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Palette.GO }
  private val badgeText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
    color = Color.WHITE; textAlign = Paint.Align.CENTER; textSize = 11f * density; isFakeBoldText = true
  }
  private val oval = RectF()
  private var phase = 0f
  private var animator: ValueAnimator? = null

  var state: BubbleState = BubbleState.IDLE
    set(value) {
      if (field == value) return
      field = value
      animator?.cancel(); animator = null
      if (animationsEnabled() && (value == BubbleState.WORKING || value == BubbleState.LISTENING)) {
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
          duration = if (value == BubbleState.WORKING) 2000 else 1600
          repeatCount = ValueAnimator.INFINITE
          if (value == BubbleState.LISTENING) repeatMode = ValueAnimator.REVERSE
          interpolator = LinearInterpolator()
          addUpdateListener { phase = it.animatedValue as Float; invalidate() }
          start()
        }
      }
      invalidate()
    }

  private fun animationsEnabled() = runCatching {
    Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) > 0f
  }.getOrDefault(true)

  override fun onDraw(canvas: Canvas) {
    val inset = stroke / 2 + density / 2
    oval.set(inset, inset, width - inset, height - inset)
    when (state) {
      BubbleState.IDLE -> Unit
      BubbleState.WORKING -> {
        arcPaint.color = Palette.MIST
        canvas.drawOval(oval, arcPaint)
        arcPaint.color = Palette.GO
        canvas.drawArc(oval, phase * 360f - 90f, 120f, false, arcPaint)
      }
      BubbleState.LISTENING -> {
        arcPaint.color = Palette.SAFFRON
        arcPaint.alpha = (255 * (0.35f + 0.65f * (1 - phase))).toInt()
        canvas.drawOval(oval, arcPaint)
        arcPaint.alpha = 255
      }
      BubbleState.WAITING -> {
        arcPaint.color = Palette.SAFFRON
        canvas.drawOval(oval, arcPaint)
        val r = 8f * density
        canvas.drawCircle(width - r, r, r, badgePaint)
        canvas.drawText("?", width - r, r + badgeText.textSize / 3, badgeText)
      }
    }
  }

  override fun onDetachedFromWindow() {
    animator?.cancel()
    super.onDetachedFromWindow()
  }
}
