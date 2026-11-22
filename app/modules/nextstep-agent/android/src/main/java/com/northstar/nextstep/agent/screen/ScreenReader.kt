package com.northstar.nextstep.agent.screen

import android.accessibilityservice.AccessibilityService
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
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

  init {
    // Enhanced security (upon mentorship): fetch ML Kit's small on-device entity model early.
    EntityMasker.prepare()
  }

  /** One element as read, kept until the privacy pass has run. */
  private class Item(val json: JSONObject, val bounds: Rect, val edit: Boolean, val password: Boolean)

  /** A screen read: package, elements, password-box flag, and boxes to black out in screenshots. */
  private class TreeRead(val pkg: String, val nodes: JSONArray, val secure: Boolean, val hide: List<Rect>)

  /**
   * Flattens the accessibility tree into visible, meaningful nodes. Text inside password fields
   * is never read (Android already masks it, and we drop it explicitly as well).
   */
  fun readTree(maxNodes: Int = 250): Triple<String, JSONArray, Boolean> =
    read(maxNodes).let { Triple(it.pkg, it.nodes, it.secure) }

  private fun read(maxNodes: Int = 250): TreeRead {
    val root = service.rootInActiveWindow ?: return TreeRead("", JSONArray(), false, emptyList())
    val out = JSONArray()
    val items = mutableListOf<Item>()
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
        val node = JSONObject().apply {
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
        }
        out.put(node)
        items += Item(node, Rect(r), n.isEditable, n.isPassword)
      }
      for (i in n.childCount - 1 downTo 0) n.getChild(i)?.let(stack::add)
    }
    val hide = protect(items, secure)
    return TreeRead(root.packageName?.toString() ?: "", out, secure, hide)
  }

  /**
   * Enhanced security (upon mentorship): hides sensitive values in place, before anything is
   * sent (see [PrivacyFilter] for the rules). Only text values change; labels, hints, ids, bounds
   * and flags stay identical, so every safety decision and tap works exactly as before.
   * Returns the boxes whose value was hidden (plus password boxes), to black out in screenshots.
   */
  private fun protect(items: List<Item>, anyPassword: Boolean): List<Rect> {
    val changed = LinkedHashSet<Item>()
    val loginScreen = anyPassword || items.any { PrivacyFilter.isLoginLabel(labelOf(it.json)) }
    fun set(item: Item, key: String, value: String?) {
      val old = item.json.optStr(key) ?: return
      if (value != null && value != old) { item.json.put(key, value); changed += item }
    }
    for (item in items) {
      if (item.password) { changed += item; continue } // text already dropped above
      val j = item.json
      if (item.edit) {
        set(item, "text", PrivacyFilter.boxValue(j.optStr("text"), j.optStr("desc"), j.optStr("id"), j.optStr("hint"), loginScreen))
        set(item, "desc", PrivacyFilter.maskText(j.optStr("desc"), phones = false))
      } else {
        set(item, "text", PrivacyFilter.maskText(j.optStr("text"), phones = true))
        set(item, "desc", PrivacyFilter.maskText(j.optStr("desc"), phones = true))
      }
    }
    val byMlKit = maskEntities(items.filter { !it.edit && !it.password }, ::set)
    // Counts only, never the values themselves.
    if (changed.isNotEmpty()) Log.i("NextStepPrivacy", "hidden on this screen: ${changed.size} (ML Kit: $byMlKit)")
    return changed.map { it.bounds }
  }

  /** ML Kit pass over read-only text, in one call for the whole screen. Returns fields changed. */
  private fun maskEntities(items: List<Item>, set: (Item, String, String?) -> Unit): Int {
    val parts = items.flatMap { item -> listOf("text", "desc").mapNotNull { k -> item.json.optStr(k)?.let { Triple(item, k, it) } } }
    val all = StringBuilder()
    val starts = parts.map { p -> all.length.also { all.append(p.third).append('\n') } }
    if (!PrivacyFilter.mayHoldLongNumber(all.toString())) return 0
    val spans = EntityMasker.find(all.toString())
    if (spans.isEmpty()) return 0
    var count = 0
    parts.forEachIndexed { i, (item, key, value) ->
      val from = starts[i]
      val local = spans.filter { it.first >= from && it.last < from + value.length }
        .map { (it.first - from)..(it.last - from) }
      if (local.isEmpty()) return@forEachIndexed
      val masked = PrivacyFilter.maskSpans(value, local)
      if (masked != value) { set(item, key, masked); count++ }
    }
    return count
  }

  private fun JSONObject.optStr(key: String): String? = if (has(key)) optString(key) else null

  private fun labelOf(j: JSONObject) =
    listOf("text", "desc", "id", "hint").mapNotNull { j.optStr(it)?.takeIf(String::isNotBlank) }.joinToString(" ")

  /**
   * Screenshot via AccessibilityService.takeScreenshot (Android 11+). Downscaled JPEG to keep
   * request size and latency low; Gemini works in normalized 0-999 coordinates anyway.
   */
  // 540 px wide is plenty for the model (it reads exact labels from the element list) and
  // roughly halves image tokens compared with 720 px.
  fun screenshot(maxWidth: Int = 540, timeoutMs: Long = 2500, hide: List<Rect> = emptyList()): String? {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
      // Android 10: MediaProjection (if the user allowed it); otherwise text-only, which works too.
      return ProjectionService.instance?.capture()?.let { encode(blackOut(it, hide), maxWidth) }
    }
    val future = CompletableFuture<String?>()
    service.takeScreenshot(Display.DEFAULT_DISPLAY, service.mainExecutor,
      object : AccessibilityService.TakeScreenshotCallback {
        override fun onSuccess(result: AccessibilityService.ScreenshotResult) {
          val hw = Bitmap.wrapHardwareBuffer(result.hardwareBuffer, result.colorSpace)
          result.hardwareBuffer.close()
          if (hw == null) { future.complete(null); return }
          val soft = hw.copy(Bitmap.Config.ARGB_8888, true)
          hw.recycle()
          future.complete(encode(blackOut(soft, hide), maxWidth))
        }
        override fun onFailure(errorCode: Int) {
          Log.w("NextStepScreen", "takeScreenshot failed: code $errorCode")
          future.complete(null)
        }
      })
    return runCatching { future.get(timeoutMs, TimeUnit.MILLISECONDS) }.getOrNull()
  }

  /**
   * Enhanced security (upon mentorship): paints black boxes over every value hidden in the text
   * (password boxes, OTPs, card / Aadhaar / phone numbers), so the picture never shows what the
   * text list hides. Huge containers are skipped so the helper can still see the screen.
   */
  private fun blackOut(bitmap: Bitmap, hide: List<Rect>): Bitmap {
    if (hide.isEmpty()) return bitmap
    val (sw, sh) = screenSize()
    if (sw <= 0 || sh <= 0) return bitmap
    val img = if (bitmap.isMutable) bitmap else bitmap.copy(Bitmap.Config.ARGB_8888, true) ?: return bitmap
    val sx = img.width.toFloat() / sw
    val sy = img.height.toFloat() / sh
    val maxArea = sw.toLong() * sh / 2
    val canvas = Canvas(img)
    val paint = Paint().apply { color = Color.BLACK; style = Paint.Style.FILL }
    for (r in hide) {
      if (r.width().toLong() * r.height() > maxArea) continue
      canvas.drawRect(r.left * sx, r.top * sy, r.right * sx, r.bottom * sy, paint)
    }
    return img
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
    val tree = read()
    val (w, h) = screenSize()
    val shot = if (withScreenshot) screenshot(hide = tree.hide) else null
    return ScreenState(tree.pkg, w, h, tree.nodes, shot, tree.secure)
  }
}
