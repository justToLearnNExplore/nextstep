package com.northstar.nextstep.agent.overlay

import android.accessibilityservice.AccessibilityService
import android.annotation.SuppressLint
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.northstar.nextstep.agent.Prefs
import com.northstar.nextstep.agent.R
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

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

data class PanelButton(val label: String, val style: Style, val onClick: () -> Unit)

interface OverlayHost {
  fun onOpenApp(key: String)
  fun onUnderstandScreen()
  fun onTalk()
  fun onCloseNextStep()
  fun onStopNow()
}

/**
 * The always-present NextStep bubble (top-right), its 4-option menu, the app picker and the task
 * panel. Drawn as TYPE_ACCESSIBILITY_OVERLAY windows, so no "draw over other apps" permission is
 * needed and it stays above every app while the accessibility service runs.
 */
class BubbleOverlay(
  private val service: AccessibilityService,
  private val prefs: Prefs,
  private val host: OverlayHost,
) {
  private val wm = service.getSystemService(WindowManager::class.java)
  private val main = Handler(Looper.getMainLooper())

  private var bubbleRoot: LinearLayout? = null
  private var bubbleRing: FrameLayout? = null
  private var stopPill: TextView? = null
  private var popup: View? = null
  private var panel: View? = null
  private var busy = false

  // ---- lifecycle -------------------------------------------------------------------------

  fun show() = main.post {
    if (bubbleRoot != null) return@post
    val ring = FrameLayout(service).apply {
      val pad = dp(4)
      setPadding(pad, pad, pad, pad)
      addView(ImageView(service).apply {
        setImageResource(R.drawable.ic_nextstep_logo)
        contentDescription = t("bubbleA11y", "NextStep. Tap for help.")
      }, FrameLayout.LayoutParams(dp(60), dp(60)))
      setOnClickListener { onBubbleTap() }
    }
    val stop = pill(t("stopNow", "Stop now"), Style.STOP) { host.onStopNow() }.apply {
      visibility = View.GONE
    }
    val root = LinearLayout(service).apply {
      orientation = LinearLayout.VERTICAL
      gravity = Gravity.CENTER_HORIZONTAL
      addView(ring)
      addView(stop, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        .apply { topMargin = dp(6) })
    }
    wm.addView(root, params(Gravity.TOP or Gravity.END, x = dp(10), y = dp(36)))
    bubbleRoot = root; bubbleRing = ring; stopPill = stop
  }

  fun remove() = main.post {
    listOfNotNull(popup, panel, bubbleRoot).forEach { runCatching { wm.removeView(it) } }
    popup = null; panel = null; bubbleRoot = null
  }

  /** Green dashed ring + red Stop pill while a task is running. */
  fun setBusy(value: Boolean) = main.post {
    busy = value
    bubbleRing?.background = if (value) GradientDrawable().apply {
      shape = GradientDrawable.OVAL
      setStroke(dp(3), Palette.GO, dp(10).toFloat(), dp(4).toFloat())
    } else null
    stopPill?.visibility = if (value) View.VISIBLE else View.GONE
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
      listOfNotNull(bubbleRoot, popup, panel).forEach { it.visibility = if (v) View.VISIBLE else View.INVISIBLE }
      done.complete(Unit)
    }
    runCatching { done.get(1, TimeUnit.SECONDS) }
    if (!v) Thread.sleep(120) // let the compositor drop the windows before capturing
  }

  // ---- menu ------------------------------------------------------------------------------

  private fun onBubbleTap() {
    when {
      popup != null -> closePopup()
      // An accidental tap must never stop or change a task; it only shows status + Stop.
      busy -> showPanel(t("working", "I'm working on it."), emptyList())
      else -> showMenu()
    }
  }

  fun showMenu(): Boolean = main.post {
    closePopupNow()
    val card = card().apply {
      addView(bigButton(t("openApp", "Open an app"), Style.OUTLINE, "▦") { showApps() })
      addView(bigButton(t("understand", "Understand this screen"), Style.OUTLINE, "◉") {
        closePopupNow(); host.onUnderstandScreen()
      })
      addView(bigButton(t("talk", "Talk to NextStep"), Style.PRIMARY, "●", tall = true) {
        closePopupNow(); host.onTalk()
      })
      addView(bigButton(t("close", "Close NextStep"), Style.OUTLINE, "⏻", small = true) {
        closePopupNow(); host.onCloseNextStep()
      })
    }
    showPopup(card)
  }

  fun showApps(): Boolean = main.post {
    closePopupNow()
    val apps = listOf(
      "whatsapp" to t("appWhatsapp", "WhatsApp"),
      "phone" to t("appPhone", "Phone"),
      "camera" to t("appCamera", "Camera"),
      "youtube" to t("appYoutube", "YouTube"),
    )
    val card = card().apply {
      addView(title(t("openApp", "Open an app")))
      addView(GridLayout(service).apply {
        columnCount = 2
        apps.forEach { (key, label) ->
          addView(tile(label) { closePopupNow(); host.onOpenApp(key) }, GridLayout.LayoutParams().apply {
            width = dp(130); height = dp(96); setMargins(dp(4), dp(4), dp(4), dp(4))
          })
        }
      })
      addView(bigButton(t("back", "Back"), Style.OUTLINE, "←", small = true) { showMenu() })
    }
    showPopup(card)
  }

  fun closePopup() = main.post { closePopupNow() }

  private fun closePopupNow() {
    popup?.let { runCatching { wm.removeView(it) } }
    popup = null
  }

  @SuppressLint("ClickableViewAccessibility")
  private fun showPopup(content: View) {
    content.setOnTouchListener { _, e ->
      if (e.action == MotionEvent.ACTION_OUTSIDE) { closePopupNow(); true } else false
    }
    val p = params(Gravity.TOP or Gravity.END, x = dp(10), y = dp(110)).apply {
      flags = flags or WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH
    }
    wm.addView(content, p)
    popup = content
  }

  // ---- task panel ------------------------------------------------------------------------

  /**
   * Bottom panel used for plans, confirmations, private-step hand-offs, explanations and scam
   * warnings. A Stop button is always appended while a task is active.
   */
  fun showPanel(message: String, buttons: List<PanelButton>, tone: Style? = null, steps: List<String> = emptyList()) =
    main.post {
      closePopupNow()
      panel?.let { runCatching { wm.removeView(it) } }
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
        buttons.forEach { b -> addView(bigButton(b.label, b.style, null) { b.onClick() }) }
        if (busy && buttons.none { it.style == Style.STOP }) {
          addView(bigButton(t("stopNow", "Stop now"), Style.STOP, null) { host.onStopNow() })
        }
      }
      wm.addView(card, params(Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL, x = 0, y = dp(24),
        width = service.resources.displayMetrics.widthPixels - dp(24)))
      panel = card
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

  private fun bigButton(
    label: String, style: Style, glyph: String?, tall: Boolean = false, small: Boolean = false,
    onClick: () -> Unit,
  ) = TextView(service).apply {
    val (bg, fg) = colors(style)
    text = if (glyph != null) "$glyph  $label" else label
    setTextColor(fg)
    setTextSize(TypedValue.COMPLEX_UNIT_SP, if (small) 18f else 21f)
    typeface = Typeface.DEFAULT_BOLD
    gravity = if (style == Style.OUTLINE && glyph != null) Gravity.CENTER_VERTICAL else Gravity.CENTER
    background = rounded(bg, if (style == Style.OUTLINE) Palette.INK else null, 14)
    minHeight = dp(if (tall) 80 else 64)
    val h = dp(16); setPadding(h, dp(10), h, dp(10))
    layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
      .apply { topMargin = dp(8) }
    isClickable = true
    setOnClickListener { onClick() }
  }

  private fun tile(label: String, onClick: () -> Unit) = TextView(service).apply {
    text = label
    setTextColor(Palette.INK)
    setTextSize(TypedValue.COMPLEX_UNIT_SP, 19f)
    typeface = Typeface.DEFAULT_BOLD
    gravity = Gravity.CENTER
    background = rounded(Color.WHITE, Palette.INK, 14)
    setOnClickListener { onClick() }
  }

  private fun pill(label: String, style: Style, onClick: () -> Unit) = TextView(service).apply {
    val (bg, fg) = colors(style)
    text = label
    setTextColor(fg)
    setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
    typeface = Typeface.DEFAULT_BOLD
    background = rounded(bg, null, 24)
    setPadding(dp(18), dp(10), dp(18), dp(10))
    setOnClickListener { onClick() }
  }
}
