package com.northstar.nextstep.agent.overlay

import android.accessibilityservice.AccessibilityService
import android.annotation.SuppressLint
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.TypedValue
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.northstar.nextstep.agent.Prefs
import com.northstar.nextstep.agent.R
import com.northstar.nextstep.agent.voice.VoiceIO
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import kotlin.math.abs

private const val TAP_GUARD_MS = 700L

/** Brand palette, tuned for ageing eyes (AAA contrast; no pale blue/green distinctions). */
object Palette {
  val INK = Color.parseColor("#12263A")
  val PAPER = Color.parseColor("#FFFDF8")
  val SAFFRON = Color.parseColor("#F2A33A")
  val GO = Color.parseColor("#155E39")
  val STOP = Color.parseColor("#A11D15")
  val AMBER = Color.parseColor("#FFF1C9")
  val MIST = Color.parseColor("#E3E6EA")
}

enum class Style { PRIMARY, OUTLINE, GO, STOP }

data class PanelButton(val label: String, val style: Style, val icon: Int? = null, val onClick: () -> Unit)

interface OverlayHost {
  fun onUnderstandScreen()
  fun onCheckScam()
  /** "Tell me": a spoken command about the current screen ("cancel my ride"). */
  fun onTellMe()
  fun onOpenNextStep()
  /** Hide the bubble until the NextStep app is opened again (the helper stays on). */
  fun onHide()
  fun onStopNow()
}

/**
 * The always-present NextStep bubble, its menu (2 big + 3 small buttons) and the task panel.
 * Drawn as TYPE_ACCESSIBILITY_OVERLAY windows, so no "draw over other apps" permission is needed.
 * Long-press and drag moves the bubble (position remembered), so it never blocks what's behind it.
 * While a task runs, the only Stop is behind a tap on the bubble: one clear place, never in the way.
 */
class BubbleOverlay(
  private val service: AccessibilityService,
  private val prefs: Prefs,
  private val host: OverlayHost,
) {
  private val wm = service.getSystemService(WindowManager::class.java)
  private val main = Handler(Looper.getMainLooper())

  private var bubbleRoot: LinearLayout? = null
  private var bubbleParams: WindowManager.LayoutParams? = null
  private var ring: StatusRing? = null
  private var popup: View? = null
  private var panel: View? = null
  private var panelToken = 0
  /** Taps on a panel that just appeared are ignored briefly, so a second or shaky tap meant for the
   *  previous panel doesn't trigger a different button in the same place. */
  private var panelShownAt = 0L
  private var busy = false
  private var hiddenInApp = false

  private val screenW get() = service.resources.displayMetrics.widthPixels
  private val screenH get() = service.resources.displayMetrics.heightPixels
  private val bubbleSize get() = dp(64)

  // ---- lifecycle -------------------------------------------------------------------------

  fun show() = main.post {
    if (bubbleRoot != null) return@post
    val statusRing = StatusRing(service)
    val head = FrameLayout(service).apply {
      addView(statusRing, FrameLayout.LayoutParams(bubbleSize, bubbleSize))
      // The logo sits just inside the status ring, so the ring visibly wraps it.
      addView(ImageView(service).apply {
        setImageResource(R.drawable.ic_nextstep_logo)
        contentDescription = t("bubbleA11y", "NextStep. Tap for help. Press and hold to move.")
      }, FrameLayout.LayoutParams(bubbleSize - dp(12), bubbleSize - dp(12), Gravity.CENTER))
    }
    val root = LinearLayout(service).apply { addView(head) }
    // Default: top-left corner. Remembered after the user moves it.
    val x = prefs.bubbleX.takeIf { it >= 0 } ?: dp(10)
    val y = prefs.bubbleY.takeIf { it >= 0 } ?: dp(36)
    val p = params(Gravity.TOP or Gravity.START, x = x, y = y)
    attachDrag(head, root, p)
    wm.addView(root, p)
    bubbleRoot = root; bubbleParams = p; ring = statusRing
    if (busy) setBusyNow(true)
  }

  fun remove() = main.post {
    listOfNotNull(popup, panel, bubbleRoot).forEach { runCatching { wm.removeView(it) } }
    popup = null; panel = null; bubbleRoot = null; ring = null
  }

  /** Hides only the bubble (e.g. while the NextStep app itself is open); panels are unaffected. */
  fun setBubbleHidden(hidden: Boolean) = main.post {
    hiddenInApp = hidden
    bubbleRoot?.visibility = if (hidden && !busy) View.GONE else View.VISIBLE
  }

  fun setState(state: BubbleState) = main.post { ring?.state = if (state == BubbleState.IDLE && busy) BubbleState.WORKING else state }

  /** Working ring while a task runs. */
  fun setBusy(value: Boolean) = main.post { setBusyNow(value) }

  private fun setBusyNow(value: Boolean) {
    busy = value
    ring?.state = if (value) BubbleState.WORKING else BubbleState.IDLE
    if (!value) closePopupNow()
    if (value) bubbleRoot?.visibility = View.VISIBLE else if (hiddenInApp) bubbleRoot?.visibility = View.GONE
  }

  /**
   * Hides every NextStep window while [block] runs on the calling (worker) thread, so screenshots
   * show the real app and taps are never swallowed by our own bubble.
   */
  fun <T> whileHidden(block: () -> T): T {
    setVisible(false)
    try { return block() } finally { setVisible(true) }
  }

  private fun setVisible(v: Boolean) {
    val done = CompletableFuture<Unit>()
    main.post {
      listOfNotNull(bubbleRoot, popup, panel).forEach {
        it.visibility = if (v && !(it === bubbleRoot && hiddenInApp && !busy)) View.VISIBLE
          else if (v) View.GONE else View.INVISIBLE
      }
      done.complete(Unit)
    }
    runCatching { done.get(1, TimeUnit.SECONDS) }
    if (!v) Thread.sleep(120) // let the compositor drop the windows before capturing
  }

  // ---- moving the bubble -----------------------------------------------------------------

  /** Tap opens the menu; press-and-hold then drag moves the bubble anywhere. */
  @SuppressLint("ClickableViewAccessibility")
  private fun attachDrag(handle: View, root: View, p: WindowManager.LayoutParams) {
    val longPress = ViewConfiguration.getLongPressTimeout().toLong()
    val slop = ViewConfiguration.get(service).scaledTouchSlop
    var downX = 0f; var downY = 0f; var startX = 0; var startY = 0
    var dragging = false; var moved = false
    val startDrag = Runnable {
      dragging = true
      handle.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
      root.animate().scaleX(1.12f).scaleY(1.12f).setDuration(120).start()
      closePopupNow()
    }
    handle.setOnTouchListener { _, e ->
      when (e.actionMasked) {
        MotionEvent.ACTION_DOWN -> {
          downX = e.rawX; downY = e.rawY; startX = p.x; startY = p.y
          dragging = false; moved = false
          main.postDelayed(startDrag, longPress)
        }
        MotionEvent.ACTION_MOVE -> {
          val dx = e.rawX - downX; val dy = e.rawY - downY
          if (!dragging && (abs(dx) > slop || abs(dy) > slop)) main.removeCallbacks(startDrag)
          if (dragging) {
            moved = true
            p.x = (startX + dx).toInt().coerceIn(0, screenW - bubbleSize)
            p.y = (startY + dy).toInt().coerceIn(0, screenH - bubbleSize)
            runCatching { wm.updateViewLayout(root, p) }
          }
        }
        MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
          main.removeCallbacks(startDrag)
          if (dragging) {
            root.animate().scaleX(1f).scaleY(1f).setDuration(120).start()
            if (moved) { prefs.bubbleX = p.x; prefs.bubbleY = p.y }
          } else if (e.actionMasked == MotionEvent.ACTION_UP &&
            abs(e.rawX - downX) < slop && abs(e.rawY - downY) < slop) {
            onBubbleTap()
          }
          dragging = false
        }
      }
      true
    }
  }

  // ---- menu ------------------------------------------------------------------------------

  private fun onBubbleTap() {
    when {
      popup != null -> closePopupNow()
      // An accidental tap must never stop or change a task: it opens a small card next to the
      // bubble (leaving any question on screen untouched) with the one Stop button.
      busy -> showStopCard()
      else -> showMenu()
    }
  }

  fun showMenu(): Boolean = main.post {
    closePopupNow()
    val card = card().apply {
      addView(bigButton(t("understand", "Explain my screen"), Style.OUTLINE, R.drawable.ic_ns_eye) {
        closePopupNow(); host.onUnderstandScreen()
      })
      addView(bigButton(t("checkScam", "Check for scam"), Style.OUTLINE, R.drawable.ic_ns_shield) {
        closePopupNow(); host.onCheckScam()
      })
      addView(LinearLayout(service).apply {
        orientation = LinearLayout.HORIZONTAL
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
          .apply { topMargin = dp(8) }
        addView(square(R.drawable.ic_ns_mic, t("tellMe", "Speak"), Style.PRIMARY) { closePopupNow(); host.onTellMe() })
        addView(square(R.drawable.ic_nextstep_logo, t("openNextStep", "Open NextStep"), Style.OUTLINE, tint = false) {
          closePopupNow(); host.onOpenNextStep()
        })
        addView(square(R.drawable.ic_ns_close, t("hideBubble", "Hide"), Style.OUTLINE) { closePopupNow(); host.onHide() })
      })
    }
    showPopup(card)
  }

  private fun showStopCard() {
    closePopupNow()
    showPopup(card().apply {
      addView(TextView(service).apply {
        text = t("working", "I'm working on it.")
        setTextColor(Palette.INK)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
        setPadding(dp(4), 0, dp(4), 0)
      })
      addView(bigButton(t("stop", "Stop"), Style.STOP, R.drawable.ic_ns_stop) { closePopupNow(); host.onStopNow() })
      addView(bigButton(t("keepGoing", "Keep going"), Style.OUTLINE, null) { closePopupNow() })
    })
  }

  fun closePopup() = main.post { closePopupNow() }

  private fun closePopupNow() {
    popup?.let { runCatching { wm.removeView(it) } }
    popup = null
  }

  /** The menu opens next to the bubble, wherever it has been moved. */
  @SuppressLint("ClickableViewAccessibility")
  private fun showPopup(content: View) {
    content.setOnTouchListener { _, e ->
      if (e.action == MotionEvent.ACTION_OUTSIDE) { closePopupNow(); true } else false
    }
    val bp = bubbleParams
    val width = minOf(dp(320), screenW - dp(16))
    val x = ((bp?.x ?: (screenW - bubbleSize)) + bubbleSize - width).coerceIn(dp(8), screenW - width - dp(8))
    val below = (bp?.y ?: 0) < screenH / 2
    val p = if (below) params(Gravity.TOP or Gravity.START, x = x, y = (bp?.y ?: 0) + bubbleSize + dp(8), width = width)
      else params(Gravity.BOTTOM or Gravity.START, x = x, y = screenH - (bp?.y ?: screenH) + dp(8), width = width)
    p.flags = p.flags or WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH
    wm.addView(content, p)
    popup = content
    panelShownAt = SystemClock.uptimeMillis()
  }

  // ---- task panel ------------------------------------------------------------------------

  /** Bottom panel used for plans, confirmations, private-step hand-offs, explanations and scam warnings. */
  fun showPanel(
    message: String, buttons: List<PanelButton>, tone: Style? = null, steps: List<String> = emptyList(),
    autoHideMs: Long? = null,
  ) =
    main.post {
      closePopupNow()
      panel?.let { runCatching { wm.removeView(it) } }
      val token = ++panelToken
      panelShownAt = SystemClock.uptimeMillis()
      val card = card(fill = if (tone == Style.STOP) Palette.AMBER else Palette.PAPER).apply {
        addView(TextView(service).apply {
          text = message
          setTextColor(Palette.INK)
          setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
          setLineSpacing(0f, 1.15f)
        })
        steps.forEach { s ->
          addView(TextView(service).apply {
            text = "✓  $s"
            setTextColor(Palette.INK)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            setPadding(0, dp(4), 0, 0)
          })
        }
        buttons.forEach { b -> addView(bigButton(b.label, b.style, b.icon) { b.onClick() }) }
      }
      wm.addView(card, params(Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL, x = 0, y = dp(24),
        width = service.resources.displayMetrics.widthPixels - dp(24)))
      panel = card
      // Only hide if no newer panel replaced this one in the meantime.
      autoHideMs?.let { ms -> main.postDelayed({ if (panelToken == token) hidePanel() }, ms) }
    }

  /**
   * The listening panel: a pulsing mic, "Listening… speak now", and bars that move with the
   * user's voice. When they stop speaking it switches to [heardText] ("Got it, one moment…").
   * Returns the callbacks VoiceIO drives.
   */
  fun showListening(prompt: String, heardText: String): VoiceIO.Feedback {
    val bars = LevelBars(service)
    val label = TextView(service)
    main.post {
      closePopupNow()
      panel?.let { runCatching { wm.removeView(it) } }
      ++panelToken
      val card = card().apply {
        addView(LinearLayout(service).apply {
          orientation = LinearLayout.HORIZONTAL
          gravity = Gravity.CENTER_VERTICAL
          addView(ImageView(service).apply {
            setImageResource(R.drawable.ic_ns_mic)
            setColorFilter(Color.WHITE)
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Palette.GO) }
            val p = dp(10); setPadding(p, p, p, p)
          }, LinearLayout.LayoutParams(dp(52), dp(52)))
          addView(label.apply {
            text = prompt
            setTextColor(Palette.INK)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(dp(14), 0, 0, 0)
          })
        })
        addView(bars, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(44)).apply { topMargin = dp(12) })
      }
      wm.addView(card, params(Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL, x = 0, y = dp(24),
        width = service.resources.displayMetrics.widthPixels - dp(24)))
      panel = card
    }
    return VoiceIO.Feedback(
      onLevel = { bars.push(it) },
      onCaptured = { label.text = heardText; bars.settle() },
    )
  }

  fun hidePanel() = main.post {
    panel?.let { runCatching { wm.removeView(it) } }
    panel = null
  }

  // ---- view helpers ----------------------------------------------------------------------

  private fun t(key: String, fallback: String) = prefs.label(key, fallback)

  private fun dp(v: Int) = TypedValue.applyDimension(
    TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), service.resources.displayMetrics).toInt()

  private fun params(gravity: Int, x: Int, y: Int, width: Int = ViewGroup.LayoutParams.WRAP_CONTENT) =
    WindowManager.LayoutParams(
      width, ViewGroup.LayoutParams.WRAP_CONTENT,
      WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
      WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
      PixelFormat.TRANSLUCENT,
    ).apply { this.gravity = gravity; this.x = x; this.y = y }

  private fun rounded(fill: Int, stroke: Int? = null, radius: Int = 16) = GradientDrawable().apply {
    cornerRadius = dp(radius).toFloat()
    setColor(fill)
    stroke?.let { setStroke(dp(2), it) }
  }

  private fun card(fill: Int = Palette.PAPER) = LinearLayout(service).apply {
    orientation = LinearLayout.VERTICAL
    background = rounded(fill, Palette.INK, 18)
    val p = dp(14); setPadding(p, p, p, p)
    elevation = dp(8).toFloat()
  }

  private fun title(s: String) = TextView(service).apply {
    text = s
    setTextColor(Palette.INK)
    setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
    typeface = Typeface.DEFAULT_BOLD
    setPadding(dp(4), 0, 0, dp(8))
  }

  private fun colors(style: Style): Pair<Int, Int> = when (style) {
    Style.PRIMARY -> Palette.INK to Color.WHITE
    Style.GO -> Palette.GO to Color.WHITE
    Style.STOP -> Palette.STOP to Color.WHITE
    Style.OUTLINE -> Color.WHITE to Palette.INK
  }

  private fun tinted(res: Int, color: Int, sizeDp: Int) = service.getDrawable(res)!!.mutate().apply {
    setTint(color); setBounds(0, 0, dp(sizeDp), dp(sizeDp))
  }

  /** Full-width button; with an icon, the icon sits in a fixed left column and the text beside it. */
  private fun bigButton(
    label: String, style: Style, icon: Int?, tall: Boolean = false, small: Boolean = false,
    onClick: () -> Unit,
  ) = TextView(service).apply {
    val (bg, fg) = colors(style)
    text = label
    setTextColor(fg)
    setTextSize(TypedValue.COMPLEX_UNIT_SP, if (small) 18f else 21f)
    typeface = Typeface.DEFAULT_BOLD
    icon?.let {
      setCompoundDrawablesRelative(tinted(it, fg, 26), null, null, null)
      compoundDrawablePadding = dp(14)
    }
    gravity = if (icon != null) Gravity.CENTER_VERTICAL or Gravity.START else Gravity.CENTER
    background = rounded(bg, if (style == Style.OUTLINE) Palette.INK else null, 14)
    minHeight = dp(if (tall) 80 else 64)
    val h = dp(16); setPadding(h, dp(10), h, dp(10))
    layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
      .apply { topMargin = dp(8) }
    isClickable = true
    setOnClickListener {
      if (SystemClock.uptimeMillis() - panelShownAt >= TAP_GUARD_MS) onClick()
    }
  }

  /** Small square menu button: one icon, read out by TalkBack as [a11y]. */
  private fun square(
    icon: Int, a11y: String, style: Style, tint: Boolean = true, onClick: () -> Unit,
  ) = LinearLayout(service).apply {
    val (bg, fg) = colors(style)
    orientation = LinearLayout.VERTICAL
    gravity = Gravity.CENTER
    background = rounded(bg, if (style == Style.OUTLINE) Palette.INK else null, 14)
    minimumHeight = dp(72)
    contentDescription = a11y
    addView(ImageView(service).apply {
      setImageResource(icon)
      if (tint) setColorFilter(fg)
      importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }, LinearLayout.LayoutParams(dp(36), dp(36)))
    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
      marginStart = dp(4); marginEnd = dp(4)
    }
    isClickable = true
    setOnClickListener { if (SystemClock.uptimeMillis() - panelShownAt >= TAP_GUARD_MS) onClick() }
  }
}
