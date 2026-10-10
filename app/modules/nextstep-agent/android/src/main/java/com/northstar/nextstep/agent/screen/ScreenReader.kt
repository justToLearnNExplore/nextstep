package com.northstar.nextstep.agent.screen

import android.accessibilityservice.AccessibilityService
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Build
import android.util.Base64
import android.util.Log
import android.view.Display
import android.view.WindowManager
import android.view.accessibility.AccessibilityNodeInfo
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

/** A compact, model-friendly snapshot of the current screen. */
data class ScreenState(
  val packageName: String,
  val width: Int,
  val height: Int,
  val nodes: JSONArray,
  val screenshotB64: String?,
  /** True when a password / secure field is visible: the agent must not act on it. */
  val hasSecureField: Boolean,
) {
  fun toJson(): JSONObject = JSONObject()
    .put("package", packageName)
    .put("width", width)
    .put("height", height)
    .put("nodes", nodes)
    .put("has_secure_field", hasSecureField)
    .apply { screenshotB64?.let { put("screenshot_b64", it) } }
}

class ScreenReader(private val service: AccessibilityService) {

  /**
   * Flattens the accessibility tree into visible, meaningful nodes. Text inside password fields
   * is never read (Android already masks it, and we drop it explicitly as well).
   */
  fun readTree(maxNodes: Int = 250): Triple<String, JSONArray, Boolean> {
    val root = service.rootInActiveWindow ?: return Triple("", JSONArray(), false)
    val out = JSONArray()
    var secure = false
    val stack = ArrayDeque<AccessibilityNodeInfo>().apply { add(root) }
    val r = Rect()
    while (stack.isNotEmpty() && out.length() < maxNodes) {
      val n = stack.removeLast()
      if (!n.isVisibleToUser) continue
      n.getBoundsInScreen(r)
      val text = n.text?.toString()?.take(120)
      val desc = n.contentDescription?.toString()?.take(120)
      val meaningful = n.isClickable || n.isEditable || n.isScrollable || !text.isNullOrBlank() || !desc.isNullOrBlank()
      if (n.isPassword) secure = true
      if (meaningful && r.width() > 0 && r.height() > 0) {
        out.put(JSONObject().apply {
          put("i", out.length())
          put("cls", n.className?.toString()?.substringAfterLast('.'))
          if (!n.isPassword) {
            text?.let { put("text", it) }
          } else {
            put("secure", true)
          }
          desc?.let { put("desc", it) }
          n.viewIdResourceName?.let { put("id", it.substringAfter(":id/")) }
          n.hintText?.toString()?.let { put("hint", it.take(80)) }
          put("b", JSONArray(listOf(r.left, r.top, r.right, r.bottom)))
          if (n.isClickable) put("click", true)
          if (n.isEditable) put("edit", true)
          if (n.isScrollable) put("scroll", true)
          if (n.isFocused) put("focus", true)
        })
      }
      for (i in n.childCount - 1 downTo 0) n.getChild(i)?.let(stack::add)
    }
    return Triple(root.packageName?.toString() ?: "", out, secure)
  }

  /**
   * Screenshot via AccessibilityService.takeScreenshot (Android 11+). Downscaled JPEG to keep
   * request size and latency low; Gemini works in normalized 0-999 coordinates anyway.
   */
  // 540 px wide is plenty for the model (it reads exact labels from the element list) and
  // roughly halves image tokens compared with 720 px.
  fun screenshot(maxWidth: Int = 540, timeoutMs: Long = 2500): String? {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
      // Android 10: MediaProjection (if the user allowed it); otherwise text-only, which works too.
      return ProjectionService.instance?.capture()?.let { encode(it, maxWidth) }
    }
    val future = CompletableFuture<String?>()
    service.takeScreenshot(Display.DEFAULT_DISPLAY, service.mainExecutor,
      object : AccessibilityService.TakeScreenshotCallback {
        override fun onSuccess(result: AccessibilityService.ScreenshotResult) {
          val hw = Bitmap.wrapHardwareBuffer(result.hardwareBuffer, result.colorSpace)
          result.hardwareBuffer.close()
          if (hw == null) { future.complete(null); return }
          val soft = hw.copy(Bitmap.Config.ARGB_8888, false)
          hw.recycle()
          future.complete(encode(soft, maxWidth))
        }
        override fun onFailure(errorCode: Int) {
          Log.w("NextStepScreen", "takeScreenshot failed: code $errorCode")
          future.complete(null)
        }
      })
    return runCatching { future.get(timeoutMs, TimeUnit.MILLISECONDS) }.getOrNull()
  }

  private fun encode(bitmap: Bitmap, maxWidth: Int): String {
    val scale = maxWidth.toFloat() / bitmap.width
    val scaled = if (scale < 1f) Bitmap.createScaledBitmap(bitmap, maxWidth, (bitmap.height * scale).toInt(), true) else bitmap
    val bytes = ByteArrayOutputStream().use { bos -> scaled.compress(Bitmap.CompressFormat.JPEG, 60, bos); bos.toByteArray() }
    return Base64.encodeToString(bytes, Base64.NO_WRAP)
  }

  /** Full physical screen size, including system bars, matching what takeScreenshot returns. */
  fun screenSize(): Pair<Int, Int> {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
      val b = service.getSystemService(WindowManager::class.java).maximumWindowMetrics.bounds
      return b.width() to b.height()
    }
    @Suppress("DEPRECATION")
    val m = android.util.DisplayMetrics().also {
      service.getSystemService(WindowManager::class.java).defaultDisplay.getRealMetrics(it)
    }
    return m.widthPixels to m.heightPixels
  }

  fun capture(withScreenshot: Boolean): ScreenState {
    val (pkg, nodes, secure) = readTree()
    val (w, h) = screenSize()
    return ScreenState(pkg, w, h, nodes, if (withScreenshot) screenshot() else null, secure)
  }
}
