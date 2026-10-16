package com.northstar.nextstep.agent.exec

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Path
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

/**
 * Executes Gemini Computer Use (mobile environment) actions on the real device.
 * Coordinates arrive normalized to 0-999 and are mapped to physical pixels.
 * Every call returns a JSON result that is sent back to the model as the function result.
 */
class ActionExecutor(private val service: AccessibilityService) {

  fun execute(name: String, args: JSONObject, w: Int, h: Int): JSONObject = runCatching {
    when (name) {
      "click" -> tap(px(args, "x", w), px(args, "y", h), 60)
      "long_press" -> tap(px(args, "x", w), px(args, "y", h), args.optLong("seconds", 2) * 1000)
      "drag_and_drop" -> swipe(
        px(args, "start_x", w), px(args, "start_y", h), px(args, "end_x", w), px(args, "end_y", h))
      "type" -> type(args.getString("text"), args.optBoolean("press_enter", false))
      "go_back" -> global(AccessibilityService.GLOBAL_ACTION_BACK)
      "press_key" -> pressKey(args.optString("key"))
      "open_app" -> openApp(args.optString("app_name"))
      "list_apps" -> ok().put("apps", listApps())
      "wait" -> { waitForChange(args.optLong("seconds", 1).coerceIn(0, 10) * 1000); ok() }
      "take_screenshot" -> ok()
      "open_play_store" -> openPlayStore(args.getString("package"))
      "open_link" -> openLink(args.getString("url"))
      "wait_for_app" -> waitForApp(args.getString("package"), args.optLong("timeout_s", 180))
      "go_home" -> global(AccessibilityService.GLOBAL_ACTION_HOME)
      else -> err("unsupported_action:$name")
    }
  }.getOrElse { err(it.message ?: it.javaClass.simpleName) }

  private fun px(a: JSONObject, k: String, size: Int) = (a.getInt(k).coerceIn(0, 999) * size / 1000f)

  private fun tap(x: Float, y: Float, durationMs: Long): JSONObject {
    val path = Path().apply { moveTo(x, y) }
    return gesture(GestureDescription.StrokeDescription(path, 0, durationMs.coerceAtLeast(1)))
  }

  private fun swipe(x1: Float, y1: Float, x2: Float, y2: Float): JSONObject {
    val path = Path().apply { moveTo(x1, y1); lineTo(x2, y2) }
    return gesture(GestureDescription.StrokeDescription(path, 0, 450))
  }

  private fun gesture(stroke: GestureDescription.StrokeDescription): JSONObject {
    val done = CompletableFuture<Boolean>()
    val accepted = service.dispatchGesture(
      GestureDescription.Builder().addStroke(stroke).build(),
      object : AccessibilityService.GestureResultCallback() {
        override fun onCompleted(d: GestureDescription?) { done.complete(true) }
        override fun onCancelled(d: GestureDescription?) { done.complete(false) }
      }, null)
    if (!accepted) return err("gesture_rejected")
    val ok = runCatching { done.get(5, TimeUnit.SECONDS) }.getOrDefault(false)
    settle()
    return if (ok) ok() else err("gesture_cancelled")
  }

  /** Types into the focused field. Refuses secure fields outright, whatever the caller says. */
  private fun type(text: String, enter: Boolean): JSONObject {
    val field = service.rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
      ?: return err("no_focused_field")
    if (field.isPassword) return err("refused_secure_field")
    val args = Bundle().apply {
      putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
    }
    if (!field.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) return err("set_text_failed")
    settle()
    if (enter && !imeEnter(field)) return ok().put("note", "enter_unsupported_tap_search_button")
    return ok()
  }

  private fun pressKey(key: String): JSONObject = when (key.lowercase()) {
    "back" -> global(AccessibilityService.GLOBAL_ACTION_BACK)
    "home" -> global(AccessibilityService.GLOBAL_ACTION_HOME)
    "recents", "app_switch" -> global(AccessibilityService.GLOBAL_ACTION_RECENTS)
    "enter" -> service.rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
      ?.let { imeEnter(it) }
      .let { if (it == true) ok() else err("enter_unsupported_tap_search_button") }
    else -> err("unsupported_key:$key")
  }

  /** "Enter"/search key: Android 11+ only. On 10 the model taps the app's search button instead. */
  private fun imeEnter(field: AccessibilityNodeInfo): Boolean =
    Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
      field.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)

  private fun global(action: Int): JSONObject {
    val ok = service.performGlobalAction(action)
    settle()
    return if (ok) ok() else err("global_action_failed")
  }

  /** Resolves a spoken/model app name ("Blinkit", "WhatsApp") to an installed launcher app. */
  fun openApp(appName: String): JSONObject {
    val pm = service.packageManager
    val match = launcherApps().firstOrNull { (label, pkg) ->
      label.equals(appName, true) || pkg.equals(appName, true)
    } ?: launcherApps().firstOrNull { (label, _) -> label.contains(appName, true) }
      ?: return err("not_installed").put("app_name", appName)
    val intent = pm.getLaunchIntentForPackage(match.second)
      ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
      ?: return err("no_launch_intent")
    service.startActivity(intent)
    settle(3000)
    return ok().put("package", match.second)
  }

  fun isInstalled(pkg: String) = runCatching {
    service.packageManager.getPackageInfo(pkg, 0); true
  }.getOrDefault(false)

  /** Opens the Play Store listing. The user taps Install themselves: we never install silently. */
  fun openPlayStore(pkg: String): JSONObject {
    val market = Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$pkg"))
      .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    try { service.startActivity(market) } catch (_: ActivityNotFoundException) {
      service.startActivity(Intent(Intent.ACTION_VIEW,
        Uri.parse("https://play.google.com/store/apps/details?id=$pkg")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
    settle(1500)
    return ok().put("note", "user_must_tap_install")
  }

  /** Allowlisted search deep links (checked by the server Guardian and again here). */
  private fun openLink(url: String): JSONObject {
    if (LINK_ALLOWLIST.none { url.startsWith(it) }) return err("link_not_allowed")
    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    // Send the link straight to its app (no "Open with" chooser for the senior to deal with).
    LINK_APPS.entries.firstOrNull { url.contains(it.key) }?.value?.takeIf(::isInstalled)?.let(intent::setPackage)
    service.startActivity(intent)
    settle(3000)
    return ok()
  }

  /**
   * After the Play Store: polls until the user has installed the app, then opens it.
   * Runs on the phone with no AI calls.
   */
  private fun waitForApp(pkg: String, timeoutS: Long): JSONObject {
    val deadline = System.currentTimeMillis() + timeoutS * 1000
    while (System.currentTimeMillis() < deadline) {
      if (isInstalled(pkg)) {
        Thread.sleep(1500) // let the installer finish writing
        val launch = service.packageManager.getLaunchIntentForPackage(pkg)
          ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) ?: return ok().put("installed", true)
        service.startActivity(launch)
        settle(3000)
        return ok().put("installed", true).put("opened", true)
      }
      Thread.sleep(2000)
    }
    return err("not_installed_in_time")
  }

  private fun launcherApps(): List<Pair<String, String>> {
    val pm = service.packageManager
    val main = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
    return pm.queryIntentActivities(main, PackageManager.MATCH_ALL).map {
      it.loadLabel(pm).toString() to it.activityInfo.packageName
    }.distinctBy { it.second }
  }

  private fun listApps() = JSONArray().apply {
    launcherApps().sortedBy { it.first }.forEach { (label, pkg) ->
      put(JSONObject().put("name", label).put("package", pkg))
    }
  }

  /**
   * Polls the active window until a node matches, e.g. WhatsApp's Send button after a share
   * intent. Returns null on timeout. Caller must not hold the node across long waits.
   */
  fun waitForNode(timeoutMs: Long, match: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
    val deadline = System.currentTimeMillis() + timeoutMs
    while (System.currentTimeMillis() < deadline) {
      service.rootInActiveWindow?.let { root -> find(root, match)?.let { return it } }
      Thread.sleep(250)
    }
    return null
  }

  private fun find(node: AccessibilityNodeInfo, match: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
    if (match(node)) return node
    for (i in 0 until node.childCount) node.getChild(i)?.let { c -> find(c, match)?.let { return it } }
    return null
  }

  /**
   * Waits until the screen stops changing (two equal reads 200 ms apart), at most [maxMs]. Most
   * taps settle in ~0.5 s, so this is much quicker than a fixed pause, yet slow apps get longer.
   */
  private fun settle(maxMs: Long = 1200) {
    Thread.sleep(MIN_SETTLE_MS)
    val deadline = System.currentTimeMillis() + maxMs - MIN_SETTLE_MS
    var last = screenSignature()
    while (System.currentTimeMillis() < deadline) {
      Thread.sleep(200)
      val now = screenSignature()
      if (now == last && now != 0) return
      last = now
    }
  }

  /** The model's "wait" (loading screens): returns as soon as the screen changes and settles. */
  private fun waitForChange(maxMs: Long) {
    val start = screenSignature()
    val deadline = System.currentTimeMillis() + maxMs
    while (System.currentTimeMillis() < deadline) {
      Thread.sleep(250)
      if (screenSignature() != start) { settle(1500); return }
    }
  }

  /** Cheap fingerprint of what's on screen: package plus the text of up to 400 nodes. */
  private fun screenSignature(): Int {
    val root = service.rootInActiveWindow ?: return 0
    var h = root.packageName?.hashCode() ?: 0
    var n = 0
    fun walk(x: AccessibilityNodeInfo?) {
      if (x == null || n++ > 400) return
      h = 31 * h + (x.text?.hashCode() ?: 0) + 7 * (x.contentDescription?.hashCode() ?: 0) + x.childCount
      for (i in 0 until x.childCount) walk(x.getChild(i))
    }
    walk(root)
    return h
  }

  private fun ok() = JSONObject().put("ok", true)

  companion object {
    private const val MIN_SETTLE_MS = 250L
    val LINK_APPS = mapOf(
      "blinkit.com" to "com.grofers.customerapp",
      "youtube.com" to "com.google.android.youtube",
      "m.uber.com" to "com.ubercab",
    )
    val LINK_ALLOWLIST = listOf(
      "https://www.youtube.com/results?search_query=",
      "https://m.youtube.com/results?search_query=",
      "https://blinkit.com/s/?q=",
      "https://m.uber.com/ul/?action=setPickup",
    )
  }
  private fun err(e: String) = JSONObject().put("ok", false).put("error", e)
}
